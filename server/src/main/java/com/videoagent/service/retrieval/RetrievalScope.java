package com.videoagent.service.retrieval;

/** 所有召回通道共用的范围约束，避免某一路遗漏用户或媒体过滤。 */
public record RetrievalScope(
        Long userId,
        Long mediaId,
        String contentHash,
        ScopeType type
) {
    public RetrievalScope {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("scope type 不能为空");
        }
        if (type == ScopeType.SINGLE_MEDIA
                && (mediaId == null || contentHash == null || contentHash.isBlank())) {
            throw new IllegalArgumentException("单视频检索必须提供 mediaId 和 contentHash");
        }
    }

    public static RetrievalScope singleMedia(Long userId, Long mediaId, String contentHash) {
        return new RetrievalScope(userId, mediaId, contentHash, ScopeType.SINGLE_MEDIA);
    }

    public static RetrievalScope userAll(Long userId) {
        return new RetrievalScope(userId, null, null, ScopeType.USER_ALL);
    }
}
