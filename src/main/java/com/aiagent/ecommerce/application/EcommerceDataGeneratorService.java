package com.aiagent.ecommerce.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.ecommerce.config.EcommerceProperties;
import com.aiagent.shared.data.TrainingQaParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * 电商客服多格式训练数据生成器
 *
 * === 设计目标 ===
 * 生成 4 种格式的训练数据文件，增强项目对不同文档类型的解析能力：
 * 1. faq_knowledge.txt       — FAQ 格式（Q:/A: 问答对）
 * 2. conversations.txt       — 多轮对话格式（User/Assistant）
 * 3. knowledge_articles.txt  — 知识文章格式（# 标题 + 正文）
 * 4. structured_data.csv     — CSV 结构化格式（question,answer,category）
 *
 * === 预算控制（3.0 元 + 对话额外 1.0 元）===
 * 模型: doubao-seed-2-0-mini (约 0.5 元/百万 tokens)
 * 总预算: 4.0 元 → 约 8,000,000 tokens
 * 分配策略:
 *   - FAQ: 20% (1.2M tokens, ~2400 条)
 *   - 对话: 30% + 额外 1.0 元 (2.8M tokens, ~1400 段)
 *   - 文章: 30% (1.8M tokens, ~600 篇)
 *   - CSV: 20% (1.2M tokens, ~3000 条)
 *
 * === 多类型解析增强 ===
 * 生成的 TXT 文件可通过 TxtDocumentParser 解析，该解析器支持：
 * - FAQ 格式自动识别（Q:/A: 标记）
 * - 多轮对话结构与分段
 * - 知识文章层级标题解析
 * - CSV 结构化字段提取
 * - 混合格式自动检测
 */
@Slf4j
@Service
public class EcommerceDataGeneratorService {

    // =============================================
    // 常量
    // =============================================

    /** 输出目录 */
    private static final String GENERATOR_MODEL_NAME = "doubao-seed-2-0-mini";
    private static final DateTimeFormatter OUTPUT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long REQUEST_INTERVAL_MS = 500L;

    private static final String OUTPUT_DIR = "generated_data";

    /** 输出文件名 */
    private static final String FILE_FAQ = "faq_knowledge.txt";
    private static final String FILE_CONVERSATIONS = "conversations.txt";
    private static final String FILE_ARTICLES = "knowledge_articles.txt";
    private static final String FILE_CSV = "structured_data.csv";
    private static final String FILE_JSONL = "qa_pairs.jsonl";

    /** 8 个电商类别 */
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "物流咨询", "退换货", "商品咨询", "价格优惠",
            "售后服务", "支付问题", "库存查询", "活动促销"
    );

    /** 成本参数（doubao-seed-2-0-mini） */
    private static final double COST_PER_MILLION_TOKENS = 0.5;  // 元/百万 tokens
    private static final double BUDGET = 3.0;                   // 总预算 3.0 元
    /** 对话模块额外预算（用户单独分配，精度最高） */
    private static final double CONVERSATION_EXTRA_BUDGET = 1.0;

    private enum GenerationFormat {
        FAQ("FAQ", "FAQ 格式（20% 预算）", FILE_FAQ, BUDGET * 0.2, "条", true, 0),
        CONVERSATION("对话", "多轮对话格式（30% 预算 + 额外 ¥1.0）",
                FILE_CONVERSATIONS, BUDGET * 0.3 + CONVERSATION_EXTRA_BUDGET, "段", false, 0),
        ARTICLE("文章", "知识文章格式（30% 预算）", FILE_ARTICLES, BUDGET * 0.3, "篇", false, 0),
        CSV("CSV", "CSV 格式（20% 预算）", FILE_CSV, BUDGET * 0.2, "条", true, 1),
        JSONL("JSONL", "JSONL 格式（10% 预算）", FILE_JSONL, BUDGET * 0.1, "条", true, 0);

        private final String label;
        private final String description;
        private final String fileName;
        private final double budget;
        private final String unit;
        private final boolean batched;
        private final int headerCount;

        GenerationFormat(String label, String description, String fileName, double budget,
                         String unit, boolean batched, int headerCount) {
            this.label = label;
            this.description = description;
            this.fileName = fileName;
            this.budget = budget;
            this.unit = unit;
            this.batched = batched;
            this.headerCount = headerCount;
        }

        private int dataCount(List<String> content) {
            return Math.max(0, content.size() - headerCount);
        }
    }

    // =============================================
    // 依赖注入
    // =============================================

    /** 专门用于数据生成的 Doubao 模型（更大 maxTokens） */
    private final dev.langchain4j.model.chat.ChatLanguageModel generatorModel;
    private final EcommerceQaPairRepository qaPairRepository;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final EcommerceProperties ecommerceProperties;

    // =============================================
    // 配置
    // =============================================




    public EcommerceDataGeneratorService(
            EcommerceQaPairRepository qaPairRepository,
            ObjectMapper objectMapper,
            AiProperties aiProperties,
            EcommerceProperties ecommerceProperties) {
        this.qaPairRepository = qaPairRepository;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.ecommerceProperties = ecommerceProperties;
        // 创建独立的 Doubao 生成模型（与主模型隔离，专用于数据生成）
        this.generatorModel = createGeneratorModel();
    }

    /**
     * 创建独立的生成模型：
     * - 使用 Doubao（低成本）
     * - 更大 maxTokens（2048）支持批量生成
     * - 温度 0.5 兼顾一致性和多样性
     */
    private dev.langchain4j.model.chat.ChatLanguageModel createGeneratorModel() {
        AiProperties.Doubao doubaoConfig = aiProperties.getModel().getDoubao();
        return OpenAiChatModel.builder()
                .baseUrl(doubaoConfig.getBaseUrl())
                .apiKey(doubaoConfig.getApiKey())
                .modelName(doubaoConfig.getModelName())
                .temperature(0.5)
                .maxTokens(2048)       // 比默认 512 更大，支持批量生成
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    // =============================================
    // 主入口
    // =============================================

    // =============================================
    // 辅助方法
    // =============================================

    private List<String> getCategories() {
        return ecommerceProperties.getGenerator().getCategories() != null && !ecommerceProperties.getGenerator().getCategories().isEmpty()
                ? ecommerceProperties.getGenerator().getCategories() : DEFAULT_CATEGORIES;
    }

    private Path getOutputDir() {
        Path outputDir = Paths.get(OUTPUT_DIR);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建输出目录失败: " + outputDir, e);
        }
        return outputDir;
    }

    // =============================================
    // 独立格式生成入口（每个格式一个方法）
    // =============================================

    /**
     * 单独生成 FAQ 格式（20% 预算）
     */
    public GenerationSummary generateFaq() {
        return generateSingle(GenerationFormat.FAQ);
    }

    /**
     * 单独生成多轮对话格式（30% 预算 + 额外 1.0 元）
     */
    public GenerationSummary generateConversations() {
        return generateSingle(GenerationFormat.CONVERSATION);
    }

    /**
     * 单独生成知识文章格式（30% 预算）
     */
    public GenerationSummary generateArticles() {
        return generateSingle(GenerationFormat.ARTICLE);
    }

    /**
     * 单独生成 CSV 格式（20% 预算）
     */
    public GenerationSummary generateCsv() {
        return generateSingle(GenerationFormat.CSV);
    }

    /**
     * 单独生成 JSONL 格式（10% 预算），并写入 MySQL
     */
    public GenerationSummary generateJsonl() {
        return generateSingle(GenerationFormat.JSONL);
    }

    private GenerationSummary generateSingle(GenerationFormat format) {
        long startTime = System.currentTimeMillis();
        List<String> categories = getCategories();
        GenerationContext context = new GenerationContext(format.budget);

        logBanner(format.label, format.description, categories.size());
        List<String> content = generateAndWrite(format, categories, getOutputDir(), context);
        persistGeneratedJsonl(format, content);
        return logAndReturnSummary(startTime, categories, context);
    }

    private void logBanner(String format, String description, int categoryCount) {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║     {} 生成: {}", format, description);
        log.info("║ 模型: {}", GENERATOR_MODEL_NAME);
        log.info("║ 类别: {} 个", categoryCount);
        log.info("╚══════════════════════════════════════════════════════╝");
    }

    private GenerationSummary logAndReturnSummary(long startTime, List<String> categories,
                                                   GenerationContext context) {
        long elapsed = System.currentTimeMillis() - startTime;
        GenerationSummary summary = buildSummary(elapsed, categories, context);
        log.info("\n{}", summary.format());
        return summary;
    }

    /**
     * 执行全量生成：5 种格式 + 8 个类别，预算控制：3.0 元上限
     */
    public GenerationSummary generateAll() {
        long startTime = System.currentTimeMillis();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();
        GenerationContext context = new GenerationContext(BUDGET + CONVERSATION_EXTRA_BUDGET);

        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║     电商客服训练数据生成器启动（全量）                  ");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║ 模型: {}", GENERATOR_MODEL_NAME);
        log.info("║ 总预算: ¥{} (约 {} tokens)", BUDGET + CONVERSATION_EXTRA_BUDGET, (long)((BUDGET + CONVERSATION_EXTRA_BUDGET) / COST_PER_MILLION_TOKENS * 1_000_000));
        log.info("║ 其中对话模块额外分配: ¥{}", CONVERSATION_EXTRA_BUDGET);
        log.info("║ 类别: {} 个", categories.size());
        log.info("║ 格式: FAQ / 对话 / 文章 / CSV / JSONL");
        log.info("╚══════════════════════════════════════════════════════╝");

        for (GenerationFormat format : GenerationFormat.values()) {
            log.info("─── 开始生成 {} 格式 ───", format.label);
            List<String> content = generateAndWrite(format, categories, outputDir, context);
            persistGeneratedJsonl(format, content);
        }

        return logAndReturnSummary(startTime, categories, context);
    }

    private List<String> generateAndWrite(GenerationFormat format, List<String> categories,
                                          Path outputDir, GenerationContext context) {
        Path outputPath = outputDir.resolve(format.fileName);
        try {
            List<String> content = generateFormat(format, categories, outputPath, context);
            writeToFile(outputPath, content);
            int dataCount = format.dataCount(content);
            context.formatCounts.put(format.label, dataCount);
            log.info("✅ {} 格式完成，共 {} {}", format.label, dataCount, format.unit);
            return content;
        } catch (Exception e) {
            log.error("❌ {} 格式生成失败: {}", format.label, e.getMessage(), e);
            return List.of();
        }
    }

    private void persistGeneratedJsonl(GenerationFormat format, List<String> content) {
        if (format == GenerationFormat.JSONL) {
            saveToMySQL(content);
        }
    }

    // =============================================
    // 格式生成器
    // =============================================

    // ---------- 1. FAQ 格式 ----------

    /**
     * 写中间结果到文件，防止进程崩溃数据丢失
     */
    private void writeIntermediate(Path filePath, List<String> entries) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                filePath, StandardCharsets.UTF_8)) {
            writer.write("=== 生成时间: " +
                    LocalDateTime.now().format(OUTPUT_TIMESTAMP) +
                    " ===\n");
            writer.write("=== 生成模型: " + GENERATOR_MODEL_NAME + " ===\n");
            writer.write("=== 条目数: " + entries.size() + " (中间结果) ===\n\n");
            for (String line : entries) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            log.error("写入中间文件失败: {}", filePath, e);
        }
    }

    private void appendIntermediate(Path filePath, List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (String entry : entries) {
                writer.write(entry);
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("追加中间文件失败: {}", filePath, e);
        }
    }

    private List<String> generateFormat(GenerationFormat format, List<String> categories,
                                        Path outputPath, GenerationContext context) {
        List<String> entries = initialContent(format);
        long maxTokens = (long) (format.budget / COST_PER_MILLION_TOKENS * 1_000_000);
        long usedTokens = 0;
        String prompt = promptFor(format);
        int targetPerCategory = ecommerceProperties.getGenerator().getTargetPerCategory();
        int batchSize = ecommerceProperties.getGenerator().getBatchSize();
        writeIntermediate(outputPath, entries);

        for (String category : categories) {
            int categoryCount = 0;
            while (categoryCount < targetPerCategory) {
                if (usedTokens >= maxTokens) {
                    return entries;
                }

                int requestedCount = format.batched
                        ? Math.min(batchSize, targetPerCategory - categoryCount)
                        : 1;
                try {
                    String response = callModel(prompt, category, requestedCount);
                    usedTokens += recordTokenUsage(prompt, category, response, context);

                    List<String> parsedEntries = parseGeneratedContent(format, response);
                    entries.addAll(parsedEntries);
                    categoryCount += Math.max(parsedEntries.size(), 1);
                    log.info("[{}][{}] 生成 {} {}, 累计 {} {}", format.label, category,
                            parsedEntries.size(), format.unit, format.dataCount(entries), format.unit);
                    appendIntermediate(outputPath, parsedEntries);
                    Thread.sleep(REQUEST_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[{}][{}] 生成任务已中断", format.label, category);
                    return entries;
                } catch (Exception e) {
                    log.error("[{}][{}] 生成失败: {}", format.label, category, e.getMessage());
                    break;
                }
            }
        }
        return entries;
    }

    private List<String> initialContent(GenerationFormat format) {
        List<String> entries = new ArrayList<>();
        if (format == GenerationFormat.CSV) {
            entries.add("question,answer,category");
        }
        return entries;
    }

    private long recordTokenUsage(String prompt, String category, String response,
                                  GenerationContext context) {
        long inputTokens = (prompt.length() + category.length()) / 2;
        long outputTokens = response.length() / 2;
        context.inputTokens += inputTokens;
        context.outputTokens += outputTokens;
        return inputTokens + outputTokens;
    }

    private String promptFor(GenerationFormat format) {
        return switch (format) {
            case FAQ -> faqPrompt();
            case CONVERSATION -> conversationPrompt();
            case ARTICLE -> articlePrompt();
            case CSV -> csvPrompt();
            case JSONL -> jsonlPrompt();
        };
    }

    private String faqPrompt() {
        return """
                你是一个电商客服FAQ生成专家。请生成%d条"%s"类别的FAQ问答。

                输出格式要求（严格遵循）：
                每条FAQ包含：
                Q: 用户问题
                A: 客服回答
                用 --- 分隔不同FAQ条目。

                要求：
                - 问题自然口语化，模拟真实顾客
                - 回答专业实用，包含具体操作指引
                - 回答长度 50-150 字
                - 覆盖该类别常见场景

                示例：
                Q: 退款需要多长时间到账？
                A: 亲，退款一般在1-3个工作日内原路返回，具体时间取决于支付方式。微信/支付宝通常24小时内到账，银行卡可能需要3-5个工作日。
                ---
                Q: 退货的运费谁承担？
                A: 如果是商品质量问题，退货运费由我们承担，您可以选择上门取件或自行寄回（运费到付）。如果是个人原因，退货运费需要您自理。
                ---
                """;
    }

    private String conversationPrompt() {
        return """
                你是一个电商客服多轮对话生成专家。请生成1段关于"%2$s"类别的客服对话，包含3-5轮交互。

                输出格式要求（严格遵循）：
                User: 顾客说的话
                Assistant: 客服的回答
                每轮交替 User/Assistant，用 --- 分隔不同对话。

                要求：
                - 对话自然流畅，模拟真实电商场景
                - 客服回答专业、耐心、有具体指引
                - 对话包含完整的问题解决过程
                - 每段对话 3-5 轮

                示例：
                User: 你好，我买的手机昨天收到的，开机后发现屏幕有一条竖线。
                Assistant: 亲，非常抱歉给您带来不好的体验！请您先拍一张屏幕问题的照片，我帮您核实一下。如果确实属于质量问题，我们可以为您办理换货。
                User: 好的，我拍好了。换货需要多久？我急用手机。
                Assistant: 换货流程一般需要3-5个工作日：您寄回→我们收到→质检→发出新机。如果您急用，建议选择上门取件，最快当天就能寄出。
                User: 好的，那我申请换货，选上门取件。
                Assistant: 好的，已为您提交换货申请，快递员会在1小时内联系您。新机发出后我会第一时间通知您。
                ---
                """;
    }

    private String articlePrompt() {
        return """
                你是一个电商知识文章生成专家。请生成1篇关于"%2$s"类别的知识文章。

                输出格式要求（严格遵循）：
                # 文章标题
                ## 类别: 类别名
                ## 关键词: 关键词1, 关键词2, 关键词3
                正文内容（200-500字，分段落）

                用 === 分隔不同文章。

                要求：
                - 文章结构清晰，有完整的信息量
                - 内容实用，可作为客服培训资料
                - 包含具体流程、规则、注意事项

                示例：
                # 退换货流程详解
                ## 类别: 售后服务
                ## 关键词: 退换货, 退款, 质检, 物流
                退换货是电商售后中最常见的场景之一。完整的退换货流程包括以下几个步骤：

                第一步：用户提交申请。用户需要在订单页面发起退换货申请，选择退换货原因（质量问题/个人原因/发错货等），并上传相关凭证（照片/视频）。

                第二步：客服审核。客服在后台审核申请，确认是否符合退换货政策。通常7天无理由退货、15天质量问题换货是行业标准。

                第三步：用户寄回商品。审核通过后，用户将商品寄回。需确保商品完好、配件齐全、包装完整。建议使用原包装寄回。

                第四步：仓库质检。仓库收到退货后，进行质检。确认商品状态与用户描述一致后，进入退款或换货环节。

                第五步：退款/换货。退款原路返回，通常1-3个工作日到账。换货则重新发出新商品。

                注意事项：
                - 食品/内衣等特殊品类不支持无理由退货
                - 退货时赠品需一并退回
                - 超过退货时效需特殊申请
                ===
                """;
    }

    private String csvPrompt() {
        return """
                你是一个电商客服数据生成专家。请生成%d条"%s"类别的QA数据，输出CSV格式。

                输出格式要求（严格遵循）：
                每行一条记录，格式为:
                问题,回答,类别

                要求：
                - 问题自然口语化
                - 回答专业实用
                - 文本中如有逗号请用中文逗号，避免CSV错位
                - 每条单独一行

                示例：
                退款需要多长时间到账？,亲，退款一般在1-3个工作日内原路返回，具体时间取决于支付方式。,售后服务
                退货的运费谁承担？,如果是商品质量问题由我们承担，个人原因需自理。,退换货
                """;
    }

    private String jsonlPrompt() {
        return """
                你是一个电商客服QA数据生成专家。请生成%d条"%s"类别的问答对，输出JSON格式。

                输出格式要求（严格遵循）：
                纯JSON数组，每个元素包含question和answer字段。
                不要任何markdown标记或其他文字。

                示例：
                [{"question":"退款需要多长时间？","answer":"亲，一般1-3个工作日原路返回。"},{"question":"退货运费谁承担？","answer":"质量问题我们承担，个人原因需自理。"}]
                """;
    }

    private List<String> parseGeneratedContent(GenerationFormat format, String response) throws IOException {
        return switch (format) {
            case FAQ -> parseDelimited(response, "\\R---\\R|\\\\n---\\\\n",
                    item -> item.startsWith("Q:") || item.startsWith("Q："), "\n---\n");
            case CONVERSATION -> parseDelimited(response, "\\R---\\R|\\\\n---\\\\n",
                    item -> item.contains("User:") && item.contains("Assistant:"), "\n---\n");
            case ARTICLE -> parseDelimited(response, "\\R===\\R|\\\\n===\\\\n",
                    item -> item.startsWith("#"), "\n===\n");
            case CSV -> parseCsv(response);
            case JSONL -> parseJsonl(response);
        };
    }

    private List<String> parseDelimited(String response, String delimiterRegex,
                                        Predicate<String> accepted, String suffix) {
        return Arrays.stream(response.split(delimiterRegex))
                .map(String::trim)
                .filter(accepted)
                .map(item -> item + suffix)
                .toList();
    }

    private List<String> parseCsv(String response) {
        return response.lines()
                .map(String::trim)
                .filter(line -> (line.contains("，") && line.contains(","))
                        || (line.contains(",") && !line.startsWith("问题")))
                .toList();
    }

    private List<String> parseJsonl(String response) throws IOException {
        List<String> entries = new ArrayList<>();
        for (QaPair pair : parseJsonResponse(response)) {
            String question = TrainingQaParser.normalizeText(pair.question);
            String answer = TrainingQaParser.normalizeText(pair.answer);
            if (question.isEmpty() || answer.isEmpty()) {
                continue;
            }

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("messages", List.of(
                    Map.of("role", "system", "content", "你是一个专业的电商客服助手"),
                    Map.of("role", "user", "content", question),
                    Map.of("role", "assistant", "content", answer)
            ));
            entries.add(objectMapper.writeValueAsString(record));
        }
        return entries;
    }

    // =============================================
    // 公共方法
    // =============================================

    /**
     * 解析 JSONL 并写入 MySQL（供后续 Milvus 导入使用）
     */
    @Transactional
    public void saveToMySQL(List<String> jsonlEntries) {
        int saved = 0;
        for (String jsonLine : jsonlEntries) {
            try {
                TrainingQaParser.TrainingQa record = TrainingQaParser.parse(objectMapper, jsonLine)
                        .orElse(null);
                if (record == null || !record.isComplete()) {
                    continue;
                }

                EcommerceQaPair pair = EcommerceQaPair.builder()
                        .question(record.question())
                        .answer(record.answer())
                        .qaText(record.embeddingText())
                        .category("generated")
                        .sourceFile("generated/data_generator")
                        .status(1)
                        .build();
                qaPairRepository.save(pair);
                saved++;
            } catch (Exception e) {
                log.warn("JSONL 写入 MySQL 失败: {}", e.getMessage());
            }
        }
        log.info("写入 MySQL: {} 条", saved);
    }

    // =============================================
    // 工具方法
    // =============================================

    /**
     * 调用模型生成
     */
    private String callModel(String template, String category, int count) {
        String prompt = template.formatted(count, category);
        return generatorModel.generate(prompt);
    }

    /**
     * 解析 JSON 响应
     */
    private List<QaPair> parseJsonResponse(String response) {
        String json = response.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            } else {
                return Collections.emptyList();
            }
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QaPair>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 写入文件
     */
    private void writeToFile(Path filePath, List<String> content) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                filePath, StandardCharsets.UTF_8)) {
            // 写入文件头
            writer.write("=== 生成时间: " +
                    LocalDateTime.now().format(OUTPUT_TIMESTAMP) +
                    " ===\n");
            writer.write("=== 生成模型: " + GENERATOR_MODEL_NAME + " ===\n");
            writer.write("=== 条目数: " + content.size() + " ===\n\n");

            for (String line : content) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
            log.info("已写入文件: {} ({} 条)", filePath.getFileName(), content.size());
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败: " + filePath, e);
        }
    }

    /**
     * 构建生成摘要
     */
    private GenerationSummary buildSummary(long elapsedMs, List<String> categories,
                                            GenerationContext context) {
        GenerationSummary summary = new GenerationSummary();
        summary.totalGenerated = context.formatCounts.values().stream().mapToInt(Integer::intValue).sum();
        summary.totalDuplicates = 0;
        summary.totalInputTokens = context.inputTokens;
        summary.totalOutputTokens = context.outputTokens;
        summary.elapsedMs = elapsedMs;
        summary.categories = List.copyOf(categories);
        summary.formatCounts = new LinkedHashMap<>(context.formatCounts);

        long totalTokens = context.inputTokens + context.outputTokens;
        summary.estimatedCost = totalTokens * COST_PER_MILLION_TOKENS / 1_000_000;
        summary.budget = context.budget;

        return summary;
    }

    // =============================================
    // 内部类
    // =============================================

    private static final class GenerationContext {
        private final double budget;
        private final Map<String, Integer> formatCounts = new LinkedHashMap<>();
        private long inputTokens;
        private long outputTokens;

        private GenerationContext(double budget) {
            this.budget = budget;
        }
    }

    @Data
    public static class QaPair {
        private String question;
        private String answer;
    }

    @Data
    public static class GenerationSummary {
        private int totalGenerated;
        private int totalDuplicates;
        private long totalInputTokens;
        private long totalOutputTokens;
        private long elapsedMs;
        private double estimatedCost;
        private double budget;
        private List<String> categories;
        private Map<String, Integer> formatCounts;

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════╗\n");
            sb.append("║              生成完成                              \n");
            sb.append("╠══════════════════════════════════════════════════════╣\n");
            sb.append("║ 输出目录: ").append(OUTPUT_DIR).append("\n");
            sb.append("║ 文件格式: \n");
            if (formatCounts != null) {
                formatCounts.forEach((fmt, count) ->
                        sb.append("║   - ").append(fmt).append(": ").append(count).append(" 条\n"));
            }
            sb.append("╠══════════════════════════════════════════════════════╣\n");
            sb.append("║ 输入 Tokens: ").append(totalInputTokens).append("\n");
            sb.append("║ 输出 Tokens: ").append(totalOutputTokens).append("\n");
            sb.append("║ 总 Tokens: ").append(totalInputTokens + totalOutputTokens).append("\n");
            sb.append(String.format("║ 预估成本: ¥%.4f / 预算 ¥%.2f\n", estimatedCost, budget));
            sb.append("║ 耗时: ").append(String.format("%.1f", elapsedMs / 1000.0)).append("s\n");
            sb.append("╚══════════════════════════════════════════════════════╝");
            return sb.toString();
        }
    }
}
