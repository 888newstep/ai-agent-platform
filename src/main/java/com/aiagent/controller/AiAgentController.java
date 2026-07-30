package com.aiagent.controller;

import com.aiagent.agent.AiAgentService;
import com.aiagent.cache.SemanticCacheService;
import com.aiagent.document.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AiAgentController {

    private final AiAgentService aiAgentService;
    private final DocumentService documentService;
    private final SemanticCacheService semanticCacheService;
    private final com.aiagent.agent.MultiAgentService multiAgentService;
    @Value("${DEEPSEEK_API_KEY}")
    private String deepseekApiKey;

    private static final Map<String, String> SYSTEM_PROMPTS = new HashMap<>();
    static {
        SYSTEM_PROMPTS.put("1A", "你是一位仙侠小说作家，请用第三人称写仙侠故事。");
        SYSTEM_PROMPTS.put("1B", """
                你是一位笔名"青云子"的仙侠小说作家，深耕仙侠文学二十年，代表作《九天真灵录》累计阅读量破十亿。
                
                【世界观设定】
                - 修炼境界：炼气（九层）→ 筑基 → 金丹 → 元婴 → 化神 → 渡劫 → 大乘 → 飞升
                - 灵气分五行：金木水火土，相生相克，修炼需顺应天道
                - 宗门分五等：散修 < 下品宗门 < 中品宗门 < 上品圣地 < 不朽仙门
                - 天材地宝分六阶：凡品、灵品、仙品、神品、混沌品、鸿蒙品
                
                【写作规范】
                - 第三人称有限视角，紧贴主角感知
                - 使用古风半白话文体，禁止现代网络用语
                - 场景描写须包含视觉、听觉、触觉中的至少两种感官
                - 每章需有明确的情绪弧线：铺垫 → 冲突 → 转折 → 余韵""");
        SYSTEM_PROMPTS.put("2A", """
                你是一位才华横溢的仙侠小说作家，想象力丰富，文笔优美。\
                请以你最擅长的方式创作仙侠故事，充分发挥你的创造力，\
                自由构建世界观、人物和情节。风格不限，写法不限，尽情展现你心中的仙侠世界。""");
        SYSTEM_PROMPTS.put("2B", """
                你是一位仙侠小说作家，必须严格遵守以下创作铁律：
                
                【必须做到】
                - 主角必须有明确弱点，不允许全知全能
                - 每个出场人物必须有独立动机，不允许工具人
                - 战斗必须包含策略博弈，不允许纯力量碾压
                - 对话必须体现人物性格差异
                
                【绝对禁止】
                - 禁止使用"突然""居然""没想到""不愧是你"
                - 禁止龙傲天式越级秒杀
                - 禁止脸谱化反派（坏人也要有合理动机）
                - 禁止超过三行的内心独白
                - 禁止以"话说""且说""却说"开头
                - 禁止同一自然段内出现两个以上感叹号
                
                【结构要求】
                - 开篇第一段必须是具象画面，禁止背景介绍开场
                - 每个场景结尾留一个未解的悬念或暗示""");
        SYSTEM_PROMPTS.put("3A", """
                你是一位硬核仙侠小说作家，擅长构建精密的修炼体系和热血战斗场面。
                
                【核心能力】
                - 修炼体系设计：每个境界有明确的突破条件、战力指标、标志性法术
                - 战斗描写：注重法术搭配、灵力消耗计算、地形利用、战术博弈
                - 升级节奏：合理分配机缘、磨砺、突破的节奏，避免一步登天
                
                【描写重点】
                - 法术释放要写清灵力运转路径和视觉效果
                - 战斗要有明确的攻守转换，写出"见招拆招"的紧张感
                - 宝物、丹药、阵法要写清品级、功效、使用限制
                - 战力对比要让读者清晰感知强弱差距
                
                【文风】
                - 节奏明快，短句为主，战斗段落用短促句式营造紧迫感
                - 数据化描写：距离、时间、灵力消耗要有具体感知""");
        SYSTEM_PROMPTS.put("3B", """
                你是一位文人气质的仙侠小说作家，追求"以仙写人，以剑写心"的境界。
                
                【核心追求】
                - 修仙即修心：每次境界突破都对应主角的心境蜕变和人生领悟
                - 以景写情：场景描写承载人物情绪，山水草木皆有心意
                - 道之争鸣：不同角色代表不同的"道"，冲突本质是价值观碰撞
                
                【描写重点】
                - 人物关系注重情感层次：师徒如父子、道侣知己、宿敌亦知音
                - 战斗写出意境：胜负不在招式，在心境高下
                - 日常段落要有烟火气：品茶、观雨、论道，于细微处见真章
                - 死亡与离别要克制，用留白代替煽情
                
                【文风】
                - 句式长短交错，留白处用短句，铺陈处用长句
                - 善用比喻和意象，将抽象情感化为具象画面
                - 对话含蓄蕴藉，话里有话，不直白说破""");
    }

    private static final String[][] COMPARE_GROUPS = {
            {"1", "详细程度", "1A", "简短版", "1B", "详尽版"},
            {"2", "约束强度", "2A", "自由版", "2B", "严格版"},
            {"3", "风格导向", "3A", "技术流", "3B", "意境流"},
    };

    // ==================== 会话管理 ====================

    @PostMapping("/session")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = aiAgentService.createSession();
        Map<String, String> response = new HashMap<>();
        response.put("sessionId", sessionId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        aiAgentService.clearSession(sessionId);
        return ResponseEntity.ok().build();
    }

    // ==================== 普通聊天 ====================

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        String response = aiAgentService.chat(sessionId, question, useRag);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("question", question);
        result.put("answer", response);
        return ResponseEntity.ok(result);
    }

    // ==================== ReAct 聊天 ====================

    @PostMapping("/react/chat")
    public ResponseEntity<Map<String, Object>> reactChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        log.info("收到 ReAct 聊天请求: sessionId={}, question={}, useRag={}", sessionId, question, useRag);
        String response = aiAgentService.reactChat(sessionId, question, useRag);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("question", question);
        result.put("answer", response);
        result.put("mode", "react");
        return ResponseEntity.ok(result);
    }

    // ==================== Multi-Agent 协作 ====================

    @PostMapping("/multi-agent/execute")
    public ResponseEntity<Map<String, Object>> multiAgentExecute(
            @RequestParam String task,
            @RequestParam(defaultValue = "") String context) {
        log.info("收到 Multi-Agent 请求: task={}", task);
        String response = multiAgentService.execute(task, context);
        Map<String, Object> result = new HashMap<>();
        result.put("task", task);
        result.put("answer", response);
        result.put("mode", "multi-agent");
        return ResponseEntity.ok(result);
    }

    // ==================== 流式聊天 ====================

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        return aiAgentService.streamChat(sessionId, question, useRag);
    }

    // ==================== 文档管理 ====================

    @PostMapping("/document/upload")
    public ResponseEntity<Map<String, String>> uploadDocument(@RequestParam("file") MultipartFile file) {
        documentService.uploadDocument(file);
        Map<String, String> response = new HashMap<>();
        response.put("message", "Document uploaded and processed successfully");
        response.put("fileName", file.getOriginalFilename());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/document/search")
    public ResponseEntity<Map<String, Object>> searchDocuments(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "0.7") double threshold) {
        var chunks = documentService.searchSimilar(query, topK, threshold);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("results", chunks);
        response.put("count", chunks.size());
        return ResponseEntity.ok(response);
    }

    // ==================== 语义缓存管理 ====================

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        semanticCacheService.clear();
        Map<String, String> response = new HashMap<>();
        response.put("message", "语义缓存已清空");
        return ResponseEntity.ok(response);
    }

    // ==================== 健康检查 ====================

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("mode", "react");
        return ResponseEntity.ok(response);
    }

    // ==================== 页面路由 ====================

    @GetMapping({"/", "/admin"})
    public String adminPage() {
        return "redirect:/admin.html";
    }

    // ==================== 小说生成 ====================

    @PostMapping("/generate")
    public String generate(@RequestParam String topic,
                           @RequestParam(defaultValue = "仙侠") String style,
                           @RequestParam(defaultValue = "1A") String promptId) {
        String prompt = String.format("请以%s的风格，写一篇关于%s的文章。", style, topic);
        String systemPrompt = SYSTEM_PROMPTS.getOrDefault(promptId, SYSTEM_PROMPTS.get(promptId));
        return calldeepseek(prompt,systemPrompt);
    }

    @PostMapping("/generate/compare")
    public SseEmitter comparePrompts(
            @RequestParam String topic,
            @RequestParam(defaultValue = "仙侠") String style,
            @RequestParam(defaultValue = "0") int group) {

        SseEmitter emitter = new SseEmitter(300_000L);
        ObjectMapper mapper = new ObjectMapper();

        new Thread(() -> {
            try {
                String userPrompt = String.format("请以%s的风格，写一篇关于%s的文章。", style, topic);
                int start = (group >= 1 && group <= 3) ? group - 1 : 0;
                int end = (group >= 1 && group <= 3) ? group : 3;

                emitter.send(SseEmitter.event().name("start")
                        .data(mapper.writeValueAsString(
                                Map.of("topic", topic, "style", style, "totalGroups", end - start))));

                for (int i = start; i < end; i++) {
                    String[] g = COMPARE_GROUPS[i];

                    emitter.send(SseEmitter.event().name("progress")
                            .data(mapper.writeValueAsString(
                                    Map.of("group", g[0], "variable", g[1], "status", "generating"))));

                    log.info("=== SSE 第{}组 [{}] 生成 prompt1({}) ===", g[0], g[1], g[2]);
                    String outputA = calldeepseek(userPrompt, SYSTEM_PROMPTS.get(g[2]));
                    log.info(">>> 第{}组 prompt1({}) 输出内容：\n{}", g[0], g[2], outputA);

                    log.info("=== SSE 第{}组 [{}] 生成 prompt2({}) ===", g[0], g[1], g[4]);
                    String outputB = calldeepseek(userPrompt, SYSTEM_PROMPTS.get(g[4]));
                    log.info(">>> 第{}组 prompt2({}) 输出内容：\n{}", g[0], g[4], outputB);

                    Map<String, Object> result = new HashMap<>();
                    result.put("group", g[0]);
                    result.put("variable", g[1]);
                    result.put("prompt1", Map.of("id", g[2], "label", g[3], "output", outputA));
                    result.put("prompt2", Map.of("id", g[4], "label", g[5], "output", outputB));

                    emitter.send(SseEmitter.event().name("group-result")
                            .data(mapper.writeValueAsString(result)));

                    log.info("=== SSE 第{}组 [{}] 对比完成，已推送 ===", g[0], g[1]);
                }

                emitter.send(SseEmitter.event().name("done").data("ALL_COMPLETED"));
                emitter.complete();
            } catch (IOException e) {
                log.warn("SSE 连接已断开: {}", e.getMessage());
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("SSE 生成异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(mapper.writeValueAsString(Map.of("error", e.getMessage()))));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }
    private String calldeepseek(String prompt,String systemPrompt) {
        Map<String,Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-v4-flash");
        List<Map<String,String>> messages = new ArrayList<>();
        messages.add(Map.of("role","system","content",systemPrompt));
        messages.add(Map.of("role","user","content",prompt));
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.8);
        requestBody.put("max_tokens", 512);
        requestBody.put("presence_penalty", 0.2);
        requestBody.put("frequency_penalty", 0.3);
        requestBody.put("stream", false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(deepseekApiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Map<String,Object>> entity = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) restTemplate.exchange(
                "https://api.deepseek.com/v1/chat/completions",
                HttpMethod.POST,
                entity,
                Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) return "";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices == null || choices.isEmpty()) return "";
        Map<String, Object> message = choices.get(0);
        Object content = message.get("content");
        return content != null ? content.toString() : "";
    }
}