package com.videoagent.service.agent;

import com.videoagent.dto.AnalysisMode;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 模式注册表（方案 §6.3）：GENERAL / LEARNING / REVIEW / CREATION 四模式，
 * 各自携带 Planner/Executor/Critic 追加指令与段落规格。
 */
@Service
public class ModeRegistry {

    private static final String GENERAL_PLANNER = "任务必须仅能依靠提供的视频证据完成；不得要求超出视频内容的外部知识。";
    private static final String GENERAL_EXECUTOR = "每条结论必须绑定至少一条证据（使用提供的时间戳）。";
    private static final String GENERAL_CRITIC = "检查：1) 所有计划任务是否被结论覆盖；2) 每条结论是否有时间戳证据支撑；3) 结论是否与证据矛盾。";

    private final Map<AnalysisMode, ModeProfile> profiles = new EnumMap<>(AnalysisMode.class);

    public ModeRegistry() {
        profiles.put(AnalysisMode.GENERAL, new ModeProfile(AnalysisMode.GENERAL,
                GENERAL_PLANNER, GENERAL_EXECUTOR, GENERAL_CRITIC, List.of()));

        profiles.put(AnalysisMode.LEARNING, new ModeProfile(AnalysisMode.LEARNING,
                GENERAL_PLANNER + " 面向学习者：拆解为可验证的知识点任务。",
                GENERAL_EXECUTOR + " 按学习者视角组织结论，并追加模式化段落：大纲/重难点/自测题/易错点。",
                GENERAL_CRITIC + " 额外检查模式化段落（outline/keypoints/quiz/pitfalls）是否齐全且有内容。",
                List.of(
                        new ModeProfile.SectionSpec("outline", "知识大纲"),
                        new ModeProfile.SectionSpec("keypoints", "重难点"),
                        new ModeProfile.SectionSpec("quiz", "自测题"),
                        new ModeProfile.SectionSpec("pitfalls", "易错点")
                )));

        profiles.put(AnalysisMode.REVIEW, new ModeProfile(AnalysisMode.REVIEW,
                GENERAL_PLANNER + " 面向审查：拆解为需要批判性核查的论证点。",
                GENERAL_EXECUTOR + " 以审查者视角给出结论，并追加模式化段落：逻辑漏洞/夸大/遗漏/存疑。",
                GENERAL_CRITIC + " 额外检查模式化段落（fallacies/exaggerations/omissions/doubtful）是否齐全且有内容。",
                List.of(
                        new ModeProfile.SectionSpec("fallacies", "逻辑漏洞"),
                        new ModeProfile.SectionSpec("exaggerations", "夸大之处"),
                        new ModeProfile.SectionSpec("omissions", "遗漏之处"),
                        new ModeProfile.SectionSpec("doubtful", "存疑之处")
                )));

        profiles.put(AnalysisMode.CREATION, new ModeProfile(AnalysisMode.CREATION,
                GENERAL_PLANNER + " 面向内容创作：拆解为可复用的素材提炼任务。",
                GENERAL_EXECUTOR + " 以创作者视角提炼素材，并追加模式化段落：爆点/标题/简介/口播脚本。",
                GENERAL_CRITIC + " 额外检查模式化段落（highlights/titles/intro/script）是否齐全且有内容。",
                List.of(
                        new ModeProfile.SectionSpec("highlights", "爆点"),
                        new ModeProfile.SectionSpec("titles", "候选标题"),
                        new ModeProfile.SectionSpec("intro", "内容简介"),
                        new ModeProfile.SectionSpec("script", "口播脚本")
                )));
    }

    public ModeProfile get(AnalysisMode mode) {
        return profiles.getOrDefault(mode, profiles.get(AnalysisMode.GENERAL));
    }

    public Map<AnalysisMode, ModeProfile> all() {
        return profiles;
    }
}
