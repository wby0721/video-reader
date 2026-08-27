package com.videoagent.service.agent;

import com.videoagent.dto.AnalysisEvidence;
import com.videoagent.dto.VideoSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executor 确定性证据绑定测试：结论 ↔ 证据包原文的最长公共子串锚点 + 窗口抽取。
 * 核心不变量：
 * 1) 有原文依据的结论必须绑定证据，且证据内容是该块原文的连续子串（L1 必过）；
 * 2) 时间戳取锚点所在 ASR 片段 startMs（秒级，不再由 LLM 猜）；
 * 3) 窗口应包含锚点上下文，把「结论与原文矛盾/超范围」暴露给 L2/Critic。
 */
class ExecutorEvidenceBindTest {

    private static final int MIN_ANCHOR = 4;

    private static EvidencePackService.EvidenceItem item(long startMs, String content) {
        return new EvidencePackService.EvidenceItem(startMs, startMs + 60_000, "ASR+OCR", content, null);
    }

    private static EvidencePackService.EvidenceItem item(long startMs, String content,
                                                         List<VideoSegment> rawSegments) {
        return new EvidencePackService.EvidenceItem(startMs, startMs + 60_000, "ASR+OCR", content, rawSegments);
    }

    private static EvidencePackService.EvidencePack pack() {
        return new EvidencePackService.EvidencePack(List.of(
                item(0, "本视频介绍IEEE802系列局域网标准。802委员会负责制定局域网和城域网的标准，"
                        + "其中IEEE802.2定义了逻辑链路控制LLC子层标准，802.3是以太网标准。"),
                item(300_000, "数据链路层分为逻辑链路控制子层和介质访问控制子层。802委员会将数据链路层分为LLC和MAC两个子层，"
                        + "MAC子层处理与传输介质相关的功能（如差错控制、透明传输、介质访问控制）。"),
                item(600_000, "无线局域网标准编号与WiFi版本对应：802.11b为WiFi1，802.11ac为WiFi5，"
                        + "802.11ax为WiFi6，802.11be为WiFi7。新版本向下兼容。")
        ), java.util.Set.of(600_000L));
    }

    @Test
    void bindsConclusion_toCorrectChunk_withVerbatimWindow() {
        List<AnalysisEvidence> ev = Executor.bindEvidence(
                List.of("数据链路层划分为LLC和MAC子层，MAC子层负责差错控制、透明传输等功能"), pack());
        assertThat(ev).hasSize(1);
        AnalysisEvidence e = ev.get(0);
        assertThat(e.timestampMs()).isEqualTo(300_000);
        assertThat(e.source()).isEqualTo("ASR+OCR");
        assertThat(e.claim()).contains("LLC和MAC");
        // 证据窗口必须包含锚点上下文：把真实原句（含"处理与传输介质相关的功能"）带出来
        assertThat(e.content()).contains("LLC和MAC两个子层");
        assertThat(e.content()).contains("差错控制、透明传输");
    }

    @Test
    void bindsConclusion_exposingFabricatedDetail_inWindow() {
        // 结论写了原文没有的"802.11n对应WiFi4"，窗口必须露出真实映射（ac→WiFi5、ax→WiFi6、be→WiFi7）
        List<AnalysisEvidence> ev = Executor.bindEvidence(
                List.of("WiFi版本对应关系：802.11n对应WiFi4，802.11ac对应WiFi5，802.11ax对应WiFi6"), pack());
        assertThat(ev).hasSize(1);
        AnalysisEvidence e = ev.get(0);
        assertThat(e.timestampMs()).isEqualTo(600_000);
        assertThat(e.content()).contains("802.11b为WiFi1");
        assertThat(e.content()).contains("802.11be为WiFi7");
        // 编造的 n→WiFi4 不在证据窗口中（原文确实没有）——矛盾留给 L2/Critic 判定
        assertThat(e.content()).doesNotContain("802.11n对应WiFi4");
    }

    @Test
    void noAnchor_conclusionGetsNoEvidence() {
        // 结论与证据包原文无 ≥MIN_ANCHOR 的公共子串 → 不绑定证据（交由 Critic/验证判无证据）
        List<AnalysisEvidence> ev = Executor.bindEvidence(List.of("完全与视频无关的编造内容abc123xyz"), pack());
        assertThat(ev).isEmpty();
    }

    @Test
    void conclusionSpanningMultipleChunks_bindsMultipleEvidence() {
        // v6.3 多证据绑定：结论横跨多个时间位置（"去中心化"在 300000 块、"单点故障不影响全局"在 600000 块）
        EvidencePackService.EvidencePack multi = new EvidencePackService.EvidencePack(List.of(
                item(0, "本视频介绍网络应用模型。"),
                item(300_000, "P2P模型最重要的一个思想就是去中心化，各主机之间的地位是平等的，主机之间可以直接通信。"),
                item(600_000, "单个节点的损坏不影响全局，只要获得种子仍可从其他主机获取资源，各节点可以分摊负载。")
        ), java.util.Set.of(600_000L));

        List<AnalysisEvidence> ev = Executor.bindEvidence(
                List.of("P2P模型去中心化，各主机地位平等，单个节点损坏不影响全局"), multi);

        assertThat(ev).hasSize(2);
        assertThat(ev).extracting(AnalysisEvidence::timestampMs)
                .containsExactlyInAnyOrder(300_000L, 600_000L);
        // 每条证据内容都是对应块原文的连续子串（L1 必过），且覆盖结论的两个关键点
        assertThat(ev).anyMatch(e -> e.timestampMs() == 300_000L && e.content().contains("去中心化"));
        assertThat(ev).anyMatch(e -> e.timestampMs() == 600_000L && e.content().contains("损坏不影响全局"));
    }

    @Test
    void evidenceWindow_isPositionedAtMatch_notAtChunkHead() {
        // 回归：lcsSpan 的 b 侧区间必须用于窗口定位——匹配在块中部时，证据窗口必须覆盖中部原文，
        // 而不是错误地落在块开头（此前把结论串的区间套用到证据块串上导致窗口定位错位）。
        String head = "头部噪声前缀内容。".repeat(20);   // 180 字，超过窗口扩展范围（±120）
        String middle = "客户必须提前知道服务器的地址，服务器不需要提前知道客户端的地址。";
        String tail = "后续是其他补充内容。".repeat(10);
        EvidencePackService.EvidencePack pack = new EvidencePackService.EvidencePack(
                List.of(item(0, head + middle + tail)), java.util.Set.of(0L));

        List<AnalysisEvidence> ev = Executor.bindEvidence(
                List.of("C/S模型中客户必须提前知道服务器地址，服务器无需提前知道客户地址"), pack);

        assertThat(ev).hasSize(1);
        String content = ev.get(0).content();
        assertThat(content).contains("客户必须提前知道服务器的地址");
        // 窗口以锚点为中心，不应从块最开头截取（定位错位的表现）
        assertThat(content).doesNotStartWith(head.substring(0, 6));
    }

    @Test
    void ascii5CharAnchor_binds_withPreciseSegmentTimestamp() {
        // v6.4 回归：结论的锚点只有 5 字符 ASCII 标识（"802.5"/"802.8"）且转写有具体片段时间戳时，
        // 必须绑定证据且时间戳精确到片段（192000/222000），而不是块级 0。
        VideoSegment seg1 = VideoSegment.of(192_000, 213_000,
                "随着以太网技术崛起802.5工作组逐渐被市场淘汰专家们也解散了", List.of(), List.of());
        VideoSegment seg2 = VideoSegment.of(222_000, 240_000,
                "802.8工作组负责fddi标准化后来打不过以太网所以也解散了", List.of(), List.of());
        EvidencePackService.EvidencePack pack = new EvidencePackService.EvidencePack(
                List.of(item(0, seg1.transcript() + " " + seg2.transcript(), List.of(seg1, seg2))),
                java.util.Set.of(0L));

        List<AnalysisEvidence> ev = Executor.bindEvidence(
                List.of("802.5令牌环网和802.8FDDI因不敌以太网已被淘汰工作组解散"), pack);

        assertThat(ev).isNotEmpty();
        assertThat(ev.get(0).timestampMs()).isIn(192_000L, 222_000L); // 秒级，非块头 0
        assertThat(ev.get(0).content()).contains("工作组");
    }

    @Test
    void asciiTokenAnchors_sameChunkMultiLocation() {
        // 结论含两个相距较远的 ASCII 标识（802.5 / 802.8），应绑定两条同块不同位置的证据
        VideoSegment seg1 = VideoSegment.of(192_000, 213_000,
                "前缀内容。".repeat(12) + "随着以太网技术崛起802.5工作组逐渐被市场淘汰专家们也解散了。"
                        + "中间过渡内容填充。".repeat(10)
                        + "802.8工作组负责fddi标准化后来打不过以太网所以也解散了" + "后缀内容。".repeat(12),
                List.of(), List.of());
        EvidencePackService.EvidencePack pack = new EvidencePackService.EvidencePack(
                List.of(item(0, seg1.transcript(), List.of(seg1))), java.util.Set.of(0L));

        List<AnalysisEvidence> ev = Executor.bindEvidence(
                List.of("802.5令牌环网和802.8FDDI因不敌以太网已被淘汰工作组解散"), pack);

        assertThat(ev).hasSize(2);
        assertThat(ev).extracting(AnalysisEvidence::timestampMs).containsOnly(192_000L);
    }

    @Test
    void lcsSpan_returnsLongestContiguousMatch() {
        int[] span = Executor.lcsSpan(Executor.normalize("LLC和MAC子层"), Executor.normalize("分为LLC和MAC两个子层"));
        int len = span[1] - span[0];
        assertThat(len).isGreaterThanOrEqualTo(MIN_ANCHOR);
        // 归一化后结论中参与匹配的区间应落在"LLC和MAC"附近
        assertThat(Executor.normalize("LLC和MAC子层").substring(span[0], span[1])).contains("llc和mac");
    }
}
