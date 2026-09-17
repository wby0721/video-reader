package com.videoagent.service.retrieval;

import com.videoagent.config.AppProperties;
import com.videoagent.dto.VideoChunk;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地 Lucene 倒排索引。内容字段和 OCR 字段使用独立查询通道，供后续 RRF 融合。
 *
 * <p>索引按 userId/contentHash/indexVersion 隔离。相同内容重新构建时先删除旧文档，
 * 避免分块策略升级或处理中断后遗留脏块。</p>
 */
@Service
public class Bm25IndexService implements AutoCloseable {

    static final String F_CHUNK_ID = "chunkId";
    static final String F_USER_ID = "userId";
    static final String F_MEDIA_ID = "mediaId";
    static final String F_CONTENT_HASH = "contentHash";
    static final String F_INDEX_VERSION = "indexVersion";
    static final String F_START_MS = "startMs";
    static final String F_END_MS = "endMs";
    static final String F_TRANSCRIPT = "transcript";
    static final String F_SUMMARY = "summary";
    static final String F_KEYWORDS = "keywords";
    static final String F_OCR = "ocrText";

    private static final String DEFAULT_INDEX_PATH = "./data/lucene-index";
    private static final String[] CONTENT_FIELDS = {F_TRANSCRIPT, F_SUMMARY, F_KEYWORDS};
    private static final Map<String, Float> CONTENT_BOOSTS = Map.of(
            F_TRANSCRIPT, 1.5f,
            F_SUMMARY, 1.0f,
            F_KEYWORDS, 2.5f);

    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;

    @org.springframework.beans.factory.annotation.Autowired
    public Bm25IndexService(AppProperties properties) throws IOException {
        this(FSDirectory.open(Path.of(resolvePath(properties))));
    }

    /** 测试构造器：允许使用纯内存 Directory。 */
    Bm25IndexService(Directory directory) throws IOException {
        this.directory = directory;
        this.analyzer = new SmartChineseAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setSimilarity(new BM25Similarity());
        this.writer = new IndexWriter(directory, config);
    }

    /** 为一个用户的一份内容原子替换全部倒排文档。 */
    public synchronized void index(Long userId, Long mediaId, String contentHash,
                                   int indexVersion, List<VideoChunk> chunks) {
        requireScope(userId, contentHash);
        try {
            writer.deleteDocuments(scopeQuery(userId, contentHash, indexVersion));
            for (VideoChunk chunk : chunks) {
                writer.addDocument(toDocument(userId, mediaId, contentHash, indexVersion, chunk));
            }
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("写入 Lucene 索引失败", e);
        }
    }

    /** BM25 内容召回：转写、摘要、关键词分字段检索。 */
    public synchronized List<Hit> searchContent(Long userId, String contentHash,
                                                int indexVersion, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        MultiFieldQueryParser parser = new MultiFieldQueryParser(CONTENT_FIELDS, analyzer, CONTENT_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        return search(userId, contentHash, indexVersion, parse(parser, query), limit);
    }

    /** OCR 增强召回：只查画面文字字段，分数与内容 BM25 保持为独立通道。 */
    public synchronized List<Hit> searchOcr(Long userId, String contentHash,
                                            int indexVersion, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        QueryParser parser = new QueryParser(F_OCR, analyzer);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        return search(userId, contentHash, indexVersion, parse(parser, query), limit);
    }

    /** 删除用户范围内某份内容的全部版本；用于最后一个媒体引用被删除时清理。 */
    public synchronized void deleteContent(Long userId, String contentHash) {
        requireScope(userId, contentHash);
        try {
            writer.deleteDocuments(scopeQuery(userId, contentHash, null));
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("删除 Lucene 索引失败", e);
        }
    }

    private List<Hit> search(Long userId, String contentHash, int indexVersion,
                             Query textQuery, int limit) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        query.add(textQuery, BooleanClause.Occur.MUST);
        query.add(new TermQueryFactory(F_USER_ID, userId.toString()).query(), BooleanClause.Occur.FILTER);
        query.add(new TermQueryFactory(F_INDEX_VERSION, String.valueOf(indexVersion)).query(), BooleanClause.Occur.FILTER);
        if (contentHash != null && !contentHash.isBlank()) {
            query.add(new TermQueryFactory(F_CONTENT_HASH, contentHash).query(), BooleanClause.Occur.FILTER);
        }

        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            TopDocs docs = searcher.search(query.build(), limit);
            List<Hit> hits = new ArrayList<>(docs.scoreDocs.length);
            for (ScoreDoc scoreDoc : docs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                hits.add(new Hit(
                        doc.get(F_CHUNK_ID),
                        doc.get(F_CONTENT_HASH),
                        Long.parseLong(doc.get(F_MEDIA_ID)),
                        doc.getField(F_START_MS).numericValue().longValue(),
                        doc.getField(F_END_MS).numericValue().longValue(),
                        scoreDoc.score));
            }
            return hits;
        } catch (IOException e) {
            throw new IllegalStateException("读取 Lucene 索引失败", e);
        }
    }

    private static Query parse(QueryParser parser, String query) {
        try {
            return parser.parse(QueryParser.escape(query.trim()));
        } catch (Exception e) {
            throw new IllegalArgumentException("检索词无法解析", e);
        }
    }

    private static Document toDocument(Long userId, Long mediaId, String contentHash,
                                       int indexVersion, VideoChunk chunk) {
        Document doc = new Document();
        doc.add(new StringField(F_CHUNK_ID, required(chunk.chunkId(), "chunkId"), Field.Store.YES));
        doc.add(new StringField(F_USER_ID, userId.toString(), Field.Store.YES));
        doc.add(new StringField(F_MEDIA_ID, mediaId.toString(), Field.Store.YES));
        doc.add(new StringField(F_CONTENT_HASH, contentHash, Field.Store.YES));
        doc.add(new StringField(F_INDEX_VERSION, String.valueOf(indexVersion), Field.Store.YES));
        doc.add(new StoredField(F_START_MS, chunk.startTime()));
        doc.add(new StoredField(F_END_MS, chunk.endTime()));
        doc.add(new TextField(F_TRANSCRIPT, value(chunk.transcript()), Field.Store.NO));
        doc.add(new TextField(F_SUMMARY, value(chunk.segmentSummary()), Field.Store.NO));
        doc.add(new TextField(F_KEYWORDS, String.join(" ", safe(chunk.keywords())), Field.Store.NO));
        doc.add(new TextField(F_OCR, String.join(" ", safe(chunk.visualTexts())), Field.Store.NO));
        return doc;
    }

    private static Query scopeQuery(Long userId, String contentHash, Integer indexVersion) {
        BooleanQuery.Builder query = new BooleanQuery.Builder()
                .add(new TermQueryFactory(F_USER_ID, userId.toString()).query(), BooleanClause.Occur.FILTER)
                .add(new TermQueryFactory(F_CONTENT_HASH, contentHash).query(), BooleanClause.Occur.FILTER);
        if (indexVersion != null) {
            query.add(new TermQueryFactory(F_INDEX_VERSION, String.valueOf(indexVersion)).query(),
                    BooleanClause.Occur.FILTER);
        }
        return query.build();
    }

    private static void requireScope(Long userId, String contentHash) {
        if (userId == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("userId 和 contentHash 不能为空");
        }
    }

    private static String resolvePath(AppProperties properties) {
        if (properties == null || properties.retrieval() == null
                || properties.retrieval().lucenePath() == null
                || properties.retrieval().lucenePath().isBlank()) {
            return DEFAULT_INDEX_PATH;
        }
        return properties.retrieval().lucenePath();
    }

    private static String required(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    @Override
    @PreDestroy
    public synchronized void close() throws IOException {
        writer.close();
        analyzer.close();
        directory.close();
    }

    public record Hit(String chunkId, String contentHash, long mediaId,
                      long startMs, long endMs, float score) {}

    /** 小包装器只用于统一 TermQuery 构造，避免查询构建处混入底层 Term 细节。 */
    private record TermQueryFactory(String field, String value) {
        Query query() {
            return new org.apache.lucene.search.TermQuery(new Term(field, value));
        }
    }
}
