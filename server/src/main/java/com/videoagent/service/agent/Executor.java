package com.videoagent.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AgentPlan;
import com.videoagent.dto.AnalysisEvidence;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.AnalysisSection;
import com.videoagent.dto.VideoSegment;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Executor 角色（方案 §6.3）：基于计划 + 证据包产出结构化结果
 * （title + conclusions + evidence + suggestions + 模式化段落）。
 *
 * <p>证据绑定是<strong>确定性抽取</strong>而非 LLM 自报：对每条结论，在证据包各块的
 * 原文中求「最长公共子串」定位锚点，再以锚点为中心抽取窗口作为逐字证据。
 * 这样 evidence.content 天然是原文子串（L1 必过）、timestampMs 自动取证据块 startMs、
 * 无原文锚点的结论自动不带证据（暴露给 Critic/验证判 UNSUPPORTED）。
 */
@Service
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    /** 结论必须与证据包原文存在的最短逐字锚点（归一化字符数），低于此值视为无原文依据。 */
    private static final int MIN_ANCHOR_LEN = 4;
    /** 证据窗口：锚点两侧各扩展的原始字符数（让 L2 蕴含判断看到上下文，窗口仍是原文子串）。 */
    private static final int ANCHOR_EXPAND = 120;
    /** 单条结论最多绑定的证据条数（结论常横跨多个时间位置，如"去中心化"与"单点容错"分属不同片段）。 */
    private static final int MAX_EVIDENCE_PER_CLAIM = 3;
    /** 证据包内容中「画面文字」前缀标记（绑定锚点优先转写部分，防止 OCR 开场白污染锚点）。 */
    private static final String VISUAL_MARKER = "\n画面文字：";

    private final ObjectMapper objectMapper;

    public Executor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnalysisResult execute(LlmClient model, AgentPlan plan, EvidencePackService.EvidencePack pack,
                                  ModeProfile profile, String feedback) {
        String feedbackText = (feedback == null || feedback.isBlank()) ? ""
                : "上一轮批评家反馈（必须逐条修正，尤其要替换掉被指为「无证据/未通过L1原文核验/与原文矛盾」的结论）：\n" + feedback;
        String sectionsSpec = profile.requiredSections().isEmpty()
                ? ""
                : "必须输出模式化段落 sections（每段 2~5 条）：\n" + profile.requiredSections().stream()
                        .map(s -> "  - {\"key\":\"" + s.key() + "\",\"title\":\"" + s.title() + "\",\"items\":[\"...\"]}")
                        .reduce((a, b) -> a + "\n" + b).orElse("");

        String prompt = """
                你是视频分析执行者。基于计划与证据片段，产出结构化分析结果。
                硬性约束：
                1. 每条结论（conclusions）必须能在证据片段中找到原文依据（系统会自动为每条结论绑定逐字原文证据）；
                2. 结论是对视频内容的忠实概括：关键名词与事实必须与证据片段原文一致，禁止编造原文没有的细节
                   （例如原文只说"802.11ac对应WiFi5、802.11ax对应WiFi6"，就不得写"802.11n对应WiFi4"）；
                3. 找不到任何原文依据的结论宁可删除，也不要硬凑；
                4. conclusions ≤5 条、每条 ≤60 字；suggestions ≤3 条；每个 section 2~3 条、每条 ≤40 字；
                5. JSON 必须完整闭合，不要省略号。
                6. 批评家要求补充的具体细节（具体数值、实例名、对比数据等）必须确实出现在证据片段中才可写入结论；
                   证据中没有就写"证据不足"并删除该结论，禁止为了迎合批评家而凭空补数。
                7. 一条结论尽量对应视频中同一处的论述；内容横跨多个位置（如"去中心化"与"单点容错"分属不同片段）时，
                   请拆成多条结论——系统会为每条结论自动绑定最多 3 条逐字原文证据。
                只输出 JSON：
                {"title":"...","conclusions":["..."],"evidence":[],"suggestions":["..."],"sections":[...]}
                %s
                用户目标：%s
                计划：%s
                证据片段：
                %s
                """.formatted(sectionsSpec, plan.understoodGoal(), plan.tasks(), pack.toPromptText());

        try {
            JsonNode node = objectMapper.readTree(extractJson(model.chat(prompt, 4000)));
            String title = node.path("title").asText("视频分析");
            List<String> conclusions = strings(node, "conclusions");
            // 证据确定性绑定（取代 LLM 自报证据：引文必为原文子串、时间戳必对、无锚点结论不带证据）
            List<AnalysisEvidence> evidence = bindEvidence(conclusions, pack);
            List<String> suggestions = strings(node, "suggestions");
            List<AnalysisSection> sections = new ArrayList<>();
            node.path("sections").forEach(s -> sections.add(new AnalysisSection(
                    s.path("key").asText(""),
                    s.path("title").asText(""),
                    strings(s, "items"))));
            if (conclusions.isEmpty()) {
                conclusions = List.of("未能生成结构化结论（解析异常）");
            }
            return AnalysisResult.of(title, conclusions, evidence, suggestions, sections, null);
        } catch (Exception e) {
            log.warn("Executor 解析失败: {}", e.getMessage());
            return AnalysisResult.of("视频分析",
                    List.of("生成失败：" + e.getMessage()), List.of(), List.of(), List.of(), "Executor 输出解析异常");
        }
    }

    /**
     * 确定性证据绑定（v6.4）：对每条结论，在所有证据块原文中找「最长公共子串」锚点——
     * 优先转写部分（防 OCR 开场白污染）；主锚点外补充结论中的显著 ASCII 标识（如 "802.5"/"802.8"）
     * 作为同块多位置锚点；时间戳精确到锚点所在 ASR 片段的 startMs（块级粗粒度 → 秒级）。
     * 一条结论最多绑定 {@link #MAX_EVIDENCE_PER_CLAIM} 条证据。
     */
    static List<AnalysisEvidence> bindEvidence(List<String> conclusions,
                                               EvidencePackService.EvidencePack pack) {
        List<AnalysisEvidence> out = new ArrayList<>();
        if (conclusions == null || pack == null || pack.items() == null) {
            return out;
        }
        for (String conclusion : conclusions) {
            if (conclusion == null || conclusion.isBlank()) {
                continue;
            }
            String cNorm = normalize(conclusion);
            if (cNorm.isEmpty()) {
                continue;
            }
            List<Candidate> candidates = new ArrayList<>();
            for (EvidencePackService.EvidenceItem item : pack.items()) {
                if (item.content() == null || item.content().isBlank()) {
                    continue;
                }
                // 1) 转写部分锚点优先（口语正文；避免画面文字/开场白污染）
                String tPart = transcriptPart(item.content());
                String tNorm = normalize(tPart);
                int[] tSpan = lcsSpan(cNorm, tNorm); // [aStart,aEnd, bStart,bEnd]
                String tMatch = cNorm.substring(tSpan[0], tSpan[1]); // 锚点文本取结论串（A）
                if (validAnchor(tMatch)) {
                    // 窗口定位必须用证据块串（B）的区间——否则窗口会落在错误位置（如块开头）
                    candidates.add(candidate(item, tPart, tNorm, tSpan[2], tSpan[3]));
                    // 1b) 补充同块多位置锚点：结论中的显著 ASCII 标识（如 "802.5" 与 "802.8" 相距较远时）
                    for (String token : asciiTokens(cNorm)) {
                        int idx = tNorm.indexOf(token);
                        if (idx >= 0 && farFromExisting(candidates, item, idx)) {
                            candidates.add(candidate(item, tPart, tNorm, idx, idx + token.length()));
                        }
                    }
                    continue;
                }
                // 2) 转写无有效锚点 → 回退画面文字部分（幻灯片事实）
                String oPart = visualPart(item.content());
                if (!oPart.isBlank()) {
                    String oNorm = normalize(oPart);
                    int[] oSpan = lcsSpan(cNorm, oNorm);
                    String oMatch = cNorm.substring(oSpan[0], oSpan[1]);
                    if (validAnchor(oMatch)) {
                        candidates.add(candidate(item, oPart, oNorm, oSpan[2], oSpan[3]));
                    }
                }
            }
            candidates.sort(Comparator.comparingInt(Candidate::len).reversed());
            List<Candidate> boundList = new ArrayList<>();
            for (Candidate c : candidates) {
                // 去重：同块内锚点相距 < 40 个归一化字符视为同一位置
                boolean dup = boundList.stream().anyMatch(p -> p.item().startMs() == c.item().startMs()
                        && Math.abs(p.rawStart() - c.rawStart()) < 40);
                if (dup) {
                    continue;
                }
                boundList.add(c);
                String content = rawSpan(c.rawBase(), c.normStart(), c.normEnd(), ANCHOR_EXPAND);
                String source = "TARGETED".equals(c.item().source()) ? "ASR+OCR" : c.item().source();
                long ts = preciseTimestamp(c.item(), c.rawStart());
                out.add(new AnalysisEvidence(ts >= 0 ? ts : c.item().startMs(), source, content, conclusion));
                if (boundList.size() >= MAX_EVIDENCE_PER_CLAIM) {
                    break;
                }
            }
        }
        return out;
    }

    /** 构造候选锚点：归一化区间 + 原文偏移（供秒级时间戳定位）。 */
    private static Candidate candidate(EvidencePackService.EvidenceItem item, String rawBase,
                                       String normBase, int normStart, int normEnd) {
        return new Candidate(item, rawBase, normStart, normEnd, normEnd - normStart,
                rawOffset(normBase, normStart));
    }

    /** 结论中的显著 ASCII 标识（≥5 字符，如 "802.5"、"802.11ac"），用于同块多位置锚点。
     *  数字↔字母切换处断开（"802.8FDDI" → "802.8" + "FDDI"），"."/"/"/"-" 作为粘合符。 */
    private static List<String> asciiTokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int lastClass = -1; // 0=数字 1=字母
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '/' || c == '-';
            if (!ascii) {
                flushToken(cur, out);
                lastClass = -1;
                continue;
            }
            int cls = (c >= '0' && c <= '9') ? 0 : (Character.isLetter(c) ? 1 : -1);
            if (cls == -1) {
                cur.append(c); // 粘合符
                continue;
            }
            if (lastClass != -1 && cls != lastClass) {
                flushToken(cur, out);
            }
            cur.append(c);
            lastClass = cls;
        }
        flushToken(cur, out);
        return out;
    }

    private static void flushToken(StringBuilder cur, List<String> out) {
        if (cur.length() >= 5) {
            out.add(cur.toString());
        }
        cur.setLength(0);
    }

    /** 同块内已有锚点（归一化区间）与当前位置（归一化偏移）相距 ≥ 40 才算新位置。 */
    private static boolean farFromExisting(List<Candidate> candidates, EvidencePackService.EvidenceItem item, int idx) {
        return candidates.stream()
                .filter(c -> c.item().startMs() == item.startMs())
                .noneMatch(c -> Math.abs(c.normStart() - idx) < 40);
    }

    /** 候选锚点：原始基准串（转写部分或画面文字部分）+ 归一化区间 + 原文偏移。 */
    private record Candidate(EvidencePackService.EvidenceItem item, String rawBase,
                             int normStart, int normEnd, int len, int rawStart) {}

    /** 取转写部分（去掉"\n画面文字："及其后的 OCR 文本）。 */
    private static String transcriptPart(String content) {
        int idx = content.indexOf(VISUAL_MARKER);
        return idx < 0 ? content : content.substring(0, idx);
    }

    /** 取画面文字部分（"\n画面文字："之后）。 */
    private static String visualPart(String content) {
        int idx = content.indexOf(VISUAL_MARKER);
        return idx < 0 ? "" : content.substring(idx + VISUAL_MARKER.length());
    }

    /**
     * 锚点有效性：长度 ≥ MIN_ANCHOR_LEN，且
     * 含至少 1 个汉字；或为纯 ASCII 具体标识（≥5 字符且以字母/数字结尾，如 "802.5"、"802.11ac"）。
     * 目的：挡住 "802."、"，802."、"P2P"、"WiFi" 这类泛化前缀/缩写/标点拼接造成的伪锚点。
     */
    private static boolean validAnchor(String matched) {
        if (matched == null || matched.length() < MIN_ANCHOR_LEN) {
            return false;
        }
        if (cjkCount(matched) >= 1) {
            return true;
        }
        char last = matched.charAt(matched.length() - 1);
        return matched.length() >= 5 && Character.isLetterOrDigit(last);
    }

    /**
     * 秒级时间戳：把锚点原文偏移映射回「块内原始 ASR 片段」，返回片段 startMs；
     * 无 rawSegments 或映射失败返回 -1（调用方回退块级 startMs）。
     * 转写拼接口径与 VideoChunkingService 一致（非空白片段以单个空格连接）。
     */
    private static long preciseTimestamp(EvidencePackService.EvidenceItem item, int rawStart) {
        if (item.rawSegments() == null) {
            return -1;
        }
        int pos = 0;
        for (VideoSegment seg : item.rawSegments()) {
            String t = seg.transcript();
            if (t == null || t.isBlank()) {
                continue;
            }
            if (rawStart < pos + t.length()) {
                return seg.startMs();
            }
            pos += t.length() + 1; // +1 对应片段间的空格
        }
        return -1;
    }

    /** 归一化偏移 → 原文偏移（跳过空白，口径与 normalize 一致）。 */
    private static int rawOffset(String normBase, int normIndex) {
        int count = 0;
        for (int k = 0; k < normBase.length(); k++) {
            if (Character.isWhitespace(normBase.charAt(k))) {
                continue;
            }
            if (count == normIndex) {
                return k;
            }
            count++;
        }
        return normBase.length();
    }

    /** 统计字符串中汉字数量（Unicode HAN 脚本）。 */
    private static int cjkCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                n++;
            }
        }
        return n;
    }

    /**
     * 最长公共子串（连续）：返回长度为 4 的数组 [aStart, aEnd, bStart, bEnd]，
     * 前两个为串 a（结论）中的匹配区间，后两个为串 b（证据块原文）中的匹配区间——
     * 锚点文本校验用 a 侧区间，窗口定位必须用 b 侧区间（否则窗口会落在错误位置）。
     */
    static int[] lcsSpan(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        int maxLen = 0, aEnd = 0, bEnd = 0;
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ca == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > maxLen) {
                        maxLen = cur[j];
                        aEnd = i;
                        bEnd = j;
                    }
                } else {
                    cur[j] = 0;
                }
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            Arrays.fill(cur, 0);
        }
        return new int[]{aEnd - maxLen, aEnd, bEnd - maxLen, bEnd};
    }

    /** 归一化（小写 + 去全部空白），与 FidelityChecker 的比对口径一致。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 把归一化区间 [normStart, normEnd) 映射回原文原始下标，并各向两侧扩展 expand 个原始字符。 */
    static String rawSpan(String content, int normStart, int normEnd, int expand) {
        int rawStart = -1, rawEnd = -1, count = 0;
        for (int k = 0; k < content.length(); k++) {
            char c = content.charAt(k);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (count == normStart) {
                rawStart = k;
            }
            if (count == normEnd) {
                rawEnd = k;
                break;
            }
            count++;
        }
        if (rawStart < 0) {
            rawStart = 0;
        }
        if (rawEnd < 0) {
            rawEnd = content.length();
        }
        int s = Math.max(0, rawStart - expand);
        int e = Math.min(content.length(), rawEnd + expand);
        return content.substring(s, e);
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        node.path(field).forEach(n -> {
            String v = n.asText("").strip();
            if (!v.isBlank()) {
                out.add(v);
            }
        });
        return out;
    }

    private static String extractJson(String text) {
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : "{}";
    }
}
