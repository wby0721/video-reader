// 后端 API 封装（Vite 代理 /api → 8081）
const TOKEN_KEY = 'va_token';
// 5MB 分片：S3/MinIO 多段上传要求除末片外每片 >= 5MB（下限），同时兼顾 Cloudflare Tunnel 慢链路
const CHUNK = 5 * 1024 * 1024;

export const api = {
  token() {
    return localStorage.getItem(TOKEN_KEY) || '';
  },
  setToken(t) {
    localStorage.setItem(TOKEN_KEY, t);
  },
  clearToken() {
    localStorage.removeItem(TOKEN_KEY);
  },
  async request(method, path, body, isForm = false) {
    const headers = {};
    const token = this.token();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    let payload;
    if (isForm) {
      payload = body;
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch('/api' + path, { method, headers, body: payload });
    const text = await res.text();
    let json;
    try { json = JSON.parse(text); } catch { json = { code: res.status, message: text }; }
    if (json.code !== 0) throw new Error(json.message || ('HTTP ' + res.status));
    return json.data;
  },
  get(path) { return this.request('GET', path); },
  post(path, body, isForm) { return this.request('POST', path, body, isForm); },
  put(path, body) { return this.request('PUT', path, body); },

  // 视频标题：修改 / 自动生成（返回更新后的 MediaDto）
  putTitle(mediaId, title) { return this.put(`/media/${mediaId}/title`, { title }); },
  autoTitle(mediaId) { return this.post(`/media/${mediaId}/title/auto`); },

  // 连续追问：基于视频上下文 + 历史对话，返回自然语言回答（含更新后的完整历史）
  chat(mediaId, query, history) { return this.post('/analysis/chat', { mediaId, query, history }); },
  chatHistory(mediaId) { return this.get(`/analysis/chat-history?mediaId=${mediaId}`); },

  /**
   * 分片上传：init → chunk（带进度回调）→ complete，返回 { id: mediaId, status, reused }。
   */
  async upload(file, onProgress) {
    const init = await this.post('/media/upload/init', { filename: file.name, totalSize: file.size });
    const parts = [];
    let offset = 0;
    let partNumber = 1;
    while (offset < file.size) {
      const end = Math.min(offset + CHUNK, file.size);
      const blob = file.slice(offset, end);
      const res = await this.request('POST',
        `/media/upload/chunk?uploadId=${encodeURIComponent(init.uploadId)}&partNumber=${partNumber}`,
        blob, true);
      const etag = String(res.etag || '').replace(/^"|"$/g, '');
      parts.push({ partNumber, etag });
      offset = end;
      partNumber++;
      if (onProgress) onProgress(offset / file.size);
    }
    const done = await this.post('/media/upload/complete', { uploadId: init.uploadId, parts });
    return { id: done.mediaId, status: done.status, reused: done.reused };
  },
};
