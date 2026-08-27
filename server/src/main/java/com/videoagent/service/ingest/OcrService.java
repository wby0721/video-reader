package com.videoagent.service.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.videoagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

/**
 * OCR 客户端：调用本地 PaddleOCR（PP-OCRv4 / RapidOCR onnxruntime）推理服务。
 * 输入关键帧图片，输出画面文字行。
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final RestClient restClient;

    public OcrService(AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.ai().ocr().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 识别单张关键帧，返回画面文字行。
     */
    public List<String> recognize(Path image) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(image.toFile()));
        OcrResponse response;
        try {
            response = restClient.post()
                    .uri("/ocr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(OcrResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("OCR 服务调用失败: " + e.getMessage(), e);
        }
        if (response == null || response.lines() == null) {
            return List.of();
        }
        return response.lines().stream().map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OcrResponse(List<String> lines) {}
}
