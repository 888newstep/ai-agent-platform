package com.aiagent.ecommerce;

import com.aiagent.config.AiProperties;
import com.aiagent.entity.EcommerceQaPair;
import com.aiagent.repository.EcommerceQaPairRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // =============================================
    // 依赖注入
    // =============================================

    /** 专门用于数据生成的 Doubao 模型（更大 maxTokens） */
    private final dev.langchain4j.model.chat.ChatLanguageModel generatorModel;
    private final EcommerceQaPairRepository qaPairRepository;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    // =============================================
    // 配置
    // =============================================

    @Value("${ecommerce.generator.target-per-category:50}")
    private int targetPerCategory;

    @Value("${ecommerce.generator.batch-size:8}")
    private int batchSize;

    @Value("${ecommerce.generator.categories:}")
    private List<String> configCategories;

    // =============================================
    // 统计
    // =============================================

    private final AtomicInteger totalGenerated = new AtomicInteger(0);
    private final AtomicInteger totalDuplicates = new AtomicInteger(0);
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final Map<String, Integer> formatCounts = new ConcurrentHashMap<>();
    private final AtomicLong totalCost = new AtomicLong(0); // 单位: 分

    public EcommerceDataGeneratorService(
            EcommerceQaPairRepository qaPairRepository,
            ObjectMapper objectMapper,
            AiProperties aiProperties) {
        this.qaPairRepository = qaPairRepository;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
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

    private void resetStats() {
        totalGenerated.set(0);
        totalDuplicates.set(0);
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCost.set(0);
        formatCounts.clear();
    }

    private List<String> getCategories() {
        return configCategories != null && !configCategories.isEmpty()
                ? configCategories : DEFAULT_CATEGORIES;
    }

    private Path getOutputDir() {
        Path outputDir = Paths.get(OUTPUT_DIR);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            log.error("创建输出目录失败: {}", outputDir, e);
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
        long startTime = System.currentTimeMillis();
        resetStats();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();

        logBanner("FAQ", "FAQ 格式（20% 预算）");

        try {
            Path faqPath = outputDir.resolve(FILE_FAQ);
            List<String> faqContent = generateFaqFormat(categories, BUDGET * 0.2, faqPath);
            writeToFile(faqPath, faqContent);
            formatCounts.put("FAQ", faqContent.size());
            log.info("✅ FAQ 格式完成，共 {} 条", faqContent.size());
        } catch (Exception e) {
            log.error("❌ FAQ 格式生成失败: {}", e.getMessage(), e);
        }

        return logAndReturnSummary(startTime, categories);
    }

    /**
     * 单独生成多轮对话格式（30% 预算 + 额外 1.0 元）
     */
    public GenerationSummary generateConversations() {
        long startTime = System.currentTimeMillis();
        resetStats();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();

        logBanner("对话", "多轮对话格式（30% 预算 + 额外 ¥1.0）");

        try {
            Path convPath = outputDir.resolve(FILE_CONVERSATIONS);
            List<String> convContent = generateConversationFormat(categories, BUDGET * 0.3 + CONVERSATION_EXTRA_BUDGET, convPath);
            writeToFile(convPath, convContent);
            formatCounts.put("对话", convContent.size());
            log.info("✅ 对话格式完成，共 {} 段", convContent.size());
        } catch (Exception e) {
            log.error("❌ 对话格式生成失败: {}", e.getMessage(), e);
        }

        return logAndReturnSummary(startTime, categories);
    }

    /**
     * 单独生成知识文章格式（30% 预算）
     */
    public GenerationSummary generateArticles() {
        long startTime = System.currentTimeMillis();
        resetStats();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();

        logBanner("文章", "知识文章格式（30% 预算）");

        try {
            Path articlePath = outputDir.resolve(FILE_ARTICLES);
            List<String> articleContent = generateArticleFormat(categories, BUDGET * 0.3, articlePath);
            writeToFile(articlePath, articleContent);
            formatCounts.put("文章", articleContent.size());
            log.info("✅ 文章格式完成，共 {} 篇", articleContent.size());
        } catch (Exception e) {
            log.error("❌ 文章格式生成失败: {}", e.getMessage(), e);
        }

        return logAndReturnSummary(startTime, categories);
    }

    /**
     * 单独生成 CSV 格式（20% 预算）
     */
    public GenerationSummary generateCsv() {
        long startTime = System.currentTimeMillis();
        resetStats();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();

        logBanner("CSV", "CSV 格式（20% 预算）");

        try {
            Path csvPath = outputDir.resolve(FILE_CSV);
            List<String> csvContent = generateCsvFormat(categories, BUDGET * 0.2, csvPath);
            writeToFile(csvPath, csvContent);
            formatCounts.put("CSV", csvContent.size());
            log.info("✅ CSV 格式完成，共 {} 条", csvContent.size());
        } catch (Exception e) {
            log.error("❌ CSV 格式生成失败: {}", e.getMessage(), e);
        }

        return logAndReturnSummary(startTime, categories);
    }

    /**
     * 单独生成 JSONL 格式（10% 预算），并写入 MySQL
     */
    public GenerationSummary generateJsonl() {
        long startTime = System.currentTimeMillis();
        resetStats();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();

        logBanner("JSONL", "JSONL 格式（10% 预算）");

        List<String> jsonlContent = new ArrayList<>();
        try {
            Path jsonlPath = outputDir.resolve(FILE_JSONL);
            jsonlContent = generateJsonlFormat(categories, BUDGET * 0.1, jsonlPath);
            writeToFile(jsonlPath, jsonlContent);
            formatCounts.put("JSONL", jsonlContent.size());
            log.info("✅ JSONL 格式完成，共 {} 条", jsonlContent.size());
        } catch (Exception e) {
            log.error("❌ JSONL 格式生成失败: {}", e.getMessage(), e);
        }

        // 写入 MySQL（用于后续 Milvus 向量化）
        saveToMySQL(jsonlContent);

        return logAndReturnSummary(startTime, categories);
    }

    private void logBanner(String format, String description) {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║     {} 生成: {}", format, description);
        log.info("║ 模型: doubao-seed-2-0-mini");
        log.info("║ 类别: {} 个", getCategories().size());
        log.info("╚══════════════════════════════════════════════════════╝");
    }

    private GenerationSummary logAndReturnSummary(long startTime, List<String> categories) {
        long elapsed = System.currentTimeMillis() - startTime;
        GenerationSummary summary = buildSummary(elapsed, categories);
        log.info("\n{}", summary.format());
        return summary;
    }

    /**
     * 执行全量生成：5 种格式 + 8 个类别，预算控制：3.0 元上限
     */
    public GenerationSummary generateAll() {
        long startTime = System.currentTimeMillis();
        resetStats();
        List<String> categories = getCategories();
        Path outputDir = getOutputDir();

        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║     电商客服训练数据生成器启动（全量）                  ");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║ 模型: doubao-seed-2-0-mini");
        log.info("║ 总预算: ¥{} (约 {} tokens)", BUDGET + CONVERSATION_EXTRA_BUDGET, (long)((BUDGET + CONVERSATION_EXTRA_BUDGET) / COST_PER_MILLION_TOKENS * 1_000_000));
        log.info("║ 其中对话模块额外分配: ¥{}", CONVERSATION_EXTRA_BUDGET);
        log.info("║ 类别: {} 个", categories.size());
        log.info("║ 格式: FAQ / 对话 / 文章 / CSV / JSONL");
        log.info("╚══════════════════════════════════════════════════════╝");

        // 1. FAQ 格式 —— 20% 预算
        try {
            log.info("─── 开始生成 FAQ 格式 ───");
            Path faqPath = outputDir.resolve(FILE_FAQ);
            List<String> faqContent = generateFaqFormat(categories, BUDGET * 0.2, faqPath);
            writeToFile(faqPath, faqContent);
            formatCounts.put("FAQ", faqContent.size());
            log.info("✅ FAQ 格式完成，共 {} 条", faqContent.size());
        } catch (Exception e) {
            log.error("❌ FAQ 格式生成失败: {}", e.getMessage(), e);
        }

        // 2. 多轮对话格式 —— 30% 预算 + 额外 1.0 元
        try {
            log.info("─── 开始生成 多轮对话 格式（额外 ¥1.0 预算）───");
            Path convPath = outputDir.resolve(FILE_CONVERSATIONS);
            List<String> conversationContent = generateConversationFormat(categories, BUDGET * 0.3 + CONVERSATION_EXTRA_BUDGET, convPath);
            writeToFile(convPath, conversationContent);
            formatCounts.put("对话", conversationContent.size());
            log.info("✅ 对话格式完成，共 {} 段", conversationContent.size());
        } catch (Exception e) {
            log.error("❌ 对话格式生成失败: {}", e.getMessage(), e);
        }

        // 3. 知识文章格式 —— 30% 预算
        try {
            log.info("─── 开始生成 知识文章 格式 ───");
            Path articlePath = outputDir.resolve(FILE_ARTICLES);
            List<String> articleContent = generateArticleFormat(categories, BUDGET * 0.3, articlePath);
            writeToFile(articlePath, articleContent);
            formatCounts.put("文章", articleContent.size());
            log.info("✅ 文章格式完成，共 {} 篇", articleContent.size());
        } catch (Exception e) {
            log.error("❌ 文章格式生成失败: {}", e.getMessage(), e);
        }

        // 4. CSV 格式 —— 20% 预算
        try {
            log.info("─── 开始生成 CSV 格式 ───");
            Path csvPath = outputDir.resolve(FILE_CSV);
            List<String> csvContent = generateCsvFormat(categories, BUDGET * 0.2, csvPath);
            writeToFile(csvPath, csvContent);
            formatCounts.put("CSV", csvContent.size());
            log.info("✅ CSV 格式完成，共 {} 条", csvContent.size());
        } catch (Exception e) {
            log.error("❌ CSV 格式生成失败: {}", e.getMessage(), e);
        }

        // 5. JSONL 格式 —— 10% 预算
        List<String> jsonlContent = new ArrayList<>();
        try {
            log.info("─── 开始生成 JSONL 格式 ───");
            Path jsonlPath = outputDir.resolve(FILE_JSONL);
            jsonlContent = generateJsonlFormat(categories, BUDGET * 0.1, jsonlPath);
            writeToFile(jsonlPath, jsonlContent);
            formatCounts.put("JSONL", jsonlContent.size());
            log.info("✅ JSONL 格式完成，共 {} 条", jsonlContent.size());
        } catch (Exception e) {
            log.error("❌ JSONL 格式生成失败: {}", e.getMessage(), e);
        }

        // 同时写入 MySQL（用于后续 Milvus 向量化）
        saveToMySQL(jsonlContent);

        long elapsed = System.currentTimeMillis() - startTime;
        GenerationSummary summary = buildSummary(elapsed, categories);
        log.info("\n{}", summary.format());
        return summary;
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
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) +
                    " ===\n");
            writer.write("=== 生成模型: doubao-seed-2-0-mini ===\n");
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

    private List<String> generateFaqFormat(List<String> categories, double budgetTokens, Path outputPath) {
        List<String> entries = new ArrayList<>();
        long maxTokens = (long) (budgetTokens / COST_PER_MILLION_TOKENS * 1_000_000);
        long usedTokens = 0;

        String prompt = """
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

        for (String category : categories) {
            int categoryCount = 0;
            while (categoryCount < targetPerCategory) {
                if (usedTokens >= maxTokens) break;
                int count = Math.min(batchSize, targetPerCategory - categoryCount);

                try {
                    String response = callModel(prompt, category, count);
                    int inputTokens = (prompt.length() + category.length()) / 2;
                    int outputTokens = response.length() / 2;
                    usedTokens += inputTokens + outputTokens;
                    totalInputTokens.addAndGet(inputTokens);
                    totalOutputTokens.addAndGet(outputTokens);

                    String[] faqItems = response.split("\n---\n|\\n---\\n");
                    int parsed = 0;
                    for (String item : faqItems) {
                        item = item.trim();
                        if (item.startsWith("Q:") || item.startsWith("Q：")) {
                            entries.add(item + "\n---\n");
                            parsed++;
                        }
                    }
                    categoryCount += Math.max(parsed, 1);
                    log.info("[FAQ][{}] 生成 {} 条, 累计 {}", category, parsed, entries.size());
                    // 每批写入中间结果，防止崩溃丢失
                    writeIntermediate(outputPath, entries);
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[FAQ][{}] 生成失败: {}", category, e.getMessage());
                    break;
                }
            }
        }
        return entries;
    }

    // ---------- 2. 多轮对话格式 ----------

    /**
     * 生成多轮对话格式训练数据
     * 格式: User: ...\nAssistant: ...\nUser: ...\nAssistant: ...\n---\n
     */
    private List<String> generateConversationFormat(List<String> categories, double budgetTokens, Path outputPath) {
        List<String> entries = new ArrayList<>();
        long maxTokens = (long) (budgetTokens / COST_PER_MILLION_TOKENS * 1_000_000);
        long usedTokens = 0;

        String prompt = """
                你是一个电商客服多轮对话生成专家。请生成1段关于"%s"类别的客服对话，包含3-5轮交互。

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

        for (String category : categories) {
            int categoryCount = 0;
            while (categoryCount < targetPerCategory) {
                if (usedTokens >= maxTokens) break;

                try {
                    String response = callModel(prompt, category, 1);
                    int inputTokens = (prompt.length() + category.length()) / 2;
                    int outputTokens = response.length() / 2;
                    usedTokens += inputTokens + outputTokens;
                    totalInputTokens.addAndGet(inputTokens);
                    totalOutputTokens.addAndGet(outputTokens);

                    String[] conversations = response.split("\n---\n|\\n---\\n");
                    int parsed = 0;
                    for (String conv : conversations) {
                        conv = conv.trim();
                        if (conv.contains("User:") && conv.contains("Assistant:")) {
                            entries.add(conv + "\n---\n");
                            parsed++;
                        }
                    }
                    categoryCount += Math.max(parsed, 1);
                    log.info("[对话][{}] 生成 1 段, 累计 {} 段", category, entries.size());
                    // 每批写入中间结果，防止崩溃丢失
                    writeIntermediate(outputPath, entries);
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[对话][{}] 生成失败: {}", category, e.getMessage());
                    break;
                }
            }
        }
        return entries;
    }

    // ---------- 3. 知识文章格式 ----------

    /**
     * 生成知识文章格式训练数据
     * 格式: # 标题\n## 类别: ...\n## 关键词: ...\n正文\n===\n
     */
    private List<String> generateArticleFormat(List<String> categories, double budgetTokens, Path outputPath) {
        List<String> entries = new ArrayList<>();
        long maxTokens = (long) (budgetTokens / COST_PER_MILLION_TOKENS * 1_000_000);
        long usedTokens = 0;

        String prompt = """
                你是一个电商知识文章生成专家。请生成1篇关于"%s"类别的知识文章。

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

        for (String category : categories) {
            int categoryCount = 0;
            while (categoryCount < targetPerCategory) {
                if (usedTokens >= maxTokens) break;

                try {
                    String response = callModel(prompt, category, 1);
                    int inputTokens = (prompt.length() + category.length()) / 2;
                    int outputTokens = response.length() / 2;
                    usedTokens += inputTokens + outputTokens;
                    totalInputTokens.addAndGet(inputTokens);
                    totalOutputTokens.addAndGet(outputTokens);

                    String[] articles = response.split("\n===\n|\\n===\\n");
                    int parsed = 0;
                    for (String article : articles) {
                        article = article.trim();
                        if (article.startsWith("#")) {
                            entries.add(article + "\n===\n");
                            parsed++;
                        }
                    }
                    categoryCount += Math.max(parsed, 1);
                    log.info("[文章][{}] 生成 1 篇, 累计 {} 篇", category, entries.size());
                    // 每批写入中间结果，防止崩溃丢失
                    writeIntermediate(outputPath, entries);
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[文章][{}] 生成失败: {}", category, e.getMessage());
                    break;
                }
            }
        }
        return entries;
    }

    // ---------- 4. CSV 格式 ----------

    /**
     * 生成 CSV 结构化格式训练数据
     * 格式: question,answer,category
     */
    private List<String> generateCsvFormat(List<String> categories, double budgetTokens, Path outputPath) {
        List<String> entries = new ArrayList<>();
        // CSV 头
        entries.add("question,answer,category");
        long maxTokens = (long) (budgetTokens / COST_PER_MILLION_TOKENS * 1_000_000);
        long usedTokens = 0;

        String prompt = """
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

        for (String category : categories) {
            int categoryCount = 0;
            while (categoryCount < targetPerCategory) {
                if (usedTokens >= maxTokens) break;
                int count = Math.min(batchSize, targetPerCategory - categoryCount);

                try {
                    String response = callModel(prompt, category, count);
                    int inputTokens = (prompt.length() + category.length()) / 2;
                    int outputTokens = response.length() / 2;
                    usedTokens += inputTokens + outputTokens;
                    totalInputTokens.addAndGet(inputTokens);
                    totalOutputTokens.addAndGet(outputTokens);

                    String[] lines = response.split("\n");
                    int parsed = 0;
                    for (String line : lines) {
                        line = line.trim();
                        if (line.contains("，") && line.contains(",")) {
                            // 兼容两种分隔符
                            entries.add(line);
                            parsed++;
                        } else if (line.contains(",") && !line.startsWith("问题")) {
                            entries.add(line);
                            parsed++;
                        }
                    }
                    categoryCount += Math.max(parsed, 1);
                    log.info("[CSV][{}] 生成 {} 条, 累计 {} 条", category, parsed, entries.size() - 1);
                    // 每批写入中间结果，防止崩溃丢失
                    writeIntermediate(outputPath, entries);
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[CSV][{}] 生成失败: {}", category, e.getMessage());
                    break;
                }
            }
        }
        return entries;
    }

    // ---------- 5. JSONL 格式 ----------

    /**
     * 生成标准 JSONL 格式（兼容原有导入流程）
     * 格式: 每行一个 JSON 对象
     */
    private List<String> generateJsonlFormat(List<String> categories, double budgetTokens, Path outputPath) {
        List<String> entries = new ArrayList<>();
        long maxTokens = (long) (budgetTokens / COST_PER_MILLION_TOKENS * 1_000_000);
        long usedTokens = 0;

        String prompt = """
                你是一个电商客服QA数据生成专家。请生成%d条"%s"类别的问答对，输出JSON格式。

                输出格式要求（严格遵循）：
                纯JSON数组，每个元素包含question和answer字段。
                不要任何markdown标记或其他文字。

                示例：
                [{"question":"退款需要多长时间？","answer":"亲，一般1-3个工作日原路返回。"},{"question":"退货运费谁承担？","answer":"质量问题我们承担，个人原因需自理。"}]
                """;

        for (String category : categories) {
            int categoryCount = 0;
            while (categoryCount < targetPerCategory) {
                if (usedTokens >= maxTokens) break;
                int count = Math.min(batchSize, targetPerCategory - categoryCount);

                try {
                    String response = callModel(prompt, category, count);
                    int inputTokens = (prompt.length() + category.length()) / 2;
                    int outputTokens = response.length() / 2;
                    usedTokens += inputTokens + outputTokens;
                    totalInputTokens.addAndGet(inputTokens);
                    totalOutputTokens.addAndGet(outputTokens);

                    // 解析 JSON 数组
                    List<QaPair> pairs = parseJsonResponse(response);
                    int parsed = 0;
                    for (QaPair pair : pairs) {
                        // 清洗
                        pair.question = cleanText(pair.question);
                        pair.answer = cleanText(pair.answer);
                        if (!pair.question.isEmpty() && !pair.answer.isEmpty()) {
                            // 构建类似 ImportService 的格式
                            Map<String, Object> msg = new LinkedHashMap<>();
                            msg.put("messages", List.of(
                                    Map.of("role", "system", "content", "你是一个专业的电商客服助手"),
                                    Map.of("role", "user", "content", pair.question),
                                    Map.of("role", "assistant", "content", pair.answer)
                            ));
                            entries.add(objectMapper.writeValueAsString(msg));
                            parsed++;
                        }
                    }
                    categoryCount += Math.max(parsed, 1);
                    log.info("[JSONL][{}] 生成 {} 条, 累计 {} 条", category, parsed, entries.size());
                    // 每批写入中间结果，防止崩溃丢失
                    writeIntermediate(outputPath, entries);
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[JSONL][{}] 生成失败: {}", category, e.getMessage());
                    break;
                }
            }
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
                @SuppressWarnings("unchecked")
                Map<String, Object> root = objectMapper.readValue(jsonLine, Map.class);
                List<Map<String, String>> messages = (List<Map<String, String>>) root.get("messages");
                if (messages == null || messages.size() < 3) continue;

                String userContent = "", assistantContent = "";
                for (Map<String, String> msg : messages) {
                    String role = msg.get("role");
                    String content = msg.get("content");
                    if ("user".equals(role)) userContent = content;
                    if ("assistant".equals(role)) assistantContent = content;
                }
                if (userContent.isEmpty() || assistantContent.isEmpty()) continue;

                String qaText = "用户问题：" + userContent + " 客服回答：" + assistantContent;

                EcommerceQaPair pair = EcommerceQaPair.builder()
                        .question(userContent)
                        .answer(assistantContent)
                        .qaText(qaText)
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
     * 文本清洗
     */
    private String cleanText(String text) {
        if (text == null) return "";
        return text.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[\\r\\n]+", " ");
    }

    /**
     * 写入文件
     */
    private void writeToFile(Path filePath, List<String> content) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                filePath, StandardCharsets.UTF_8)) {
            // 写入文件头
            writer.write("=== 生成时间: " +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) +
                    " ===\n");
            writer.write("=== 生成模型: doubao-seed-2-0-mini ===\n");
            writer.write("=== 条目数: " + content.size() + " ===\n\n");

            for (String line : content) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
            log.info("已写入文件: {} ({} 条)", filePath.getFileName(), content.size());
        } catch (IOException e) {
            log.error("写入文件失败: {}", filePath, e);
        }
    }

    /**
     * 构建生成摘要
     */
    private GenerationSummary buildSummary(long elapsedMs, List<String> categories) {
        GenerationSummary summary = new GenerationSummary();
        summary.totalGenerated = totalGenerated.get();
        summary.totalDuplicates = totalDuplicates.get();
        summary.totalInputTokens = totalInputTokens.get();
        summary.totalOutputTokens = totalOutputTokens.get();
        summary.elapsedMs = elapsedMs;
        summary.categories = categories;
        summary.formatCounts = new HashMap<>(formatCounts);

        long totalTokens = totalInputTokens.get() + totalOutputTokens.get();
        summary.estimatedCost = totalTokens * COST_PER_MILLION_TOKENS / 1_000_000;
        summary.budget = BUDGET + CONVERSATION_EXTRA_BUDGET;

        return summary;
    }

    // =============================================
    // 内部类
    // =============================================

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