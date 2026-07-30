package com.aiagent.document.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT 多格式文档解析器
 *
 * 支持解析以下 5 种格式的 TXT 文件，自动检测格式并提取结构化内容：
 *
 * 1. FAQ 格式（Q:/A: 标记）
 *    示例：
 *      Q: 退款需要多长时间？
 *      A: 亲，一般1-3个工作日。
 *      ---
 *      Q: 如何申请退货？
 *      A: 在订单页面申请即可。
 *
 * 2. 多轮对话格式（User/Assistant 标记）
 *    示例：
 *      User: 你好，我想退货。
 *      Assistant: 好的，请提供订单号。
 *      User: 订单号是12345。
 *      Assistant: 已为您提交申请。
 *      ---
 *
 * 3. 知识文章格式（Markdown 风格标题）
 *    示例：
 *      # 退换货流程详解
 *      ## 类别: 售后服务
 *      ## 关键词: 退换货,退款
 *      正文内容...
 *      ===
 *
 * 4. CSV 结构化格式
 *    示例：
 *      question,answer,category
 *      退款需要多长时间？,亲，一般1-3个工作日。,售后服务
 *
 * 5. 混合格式 / 纯文本（兜底处理）
 *
 * 设计目标：
 * - 增强项目对多样化文档类型的解析能力
 * - 同一个文件可能包含多种格式段落，自动区分
 * - 提取的内容保留语义信息，适合 Embedding 后检索
 */
@Slf4j
@Component
public class TxtDocumentParser implements DocumentParser {

    /** 格式检测阈值：某种格式的段落占比超过此值即判定为该格式 */
    private static final double FORMAT_DETECT_THRESHOLD = 0.5;

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt");
    }

    @Override
    public String parse(InputStream inputStream) {
        try {
            String rawContent = readAll(inputStream);
            if (rawContent.isBlank()) {
                log.warn("TXT 文件内容为空");
                return "";
            }

            // 1. 检测文件格式
            DocumentFormat format = detectFormat(rawContent);
            log.info("检测到 TXT 格式: {} (内容长度: {})", format, rawContent.length());

            // 2. 按格式解析
            return switch (format) {
                case FAQ -> parseFaqFormat(rawContent);
                case CONVERSATION -> parseConversationFormat(rawContent);
                case ARTICLE -> parseArticleFormat(rawContent);
                case CSV -> parseCsvFormat(rawContent);
                case MIXED -> parseMixedFormat(rawContent);
                case PLAIN -> rawContent; // 纯文本直接返回
            };

        } catch (IOException e) {
            log.error("TXT 文件读取失败", e);
            throw new RuntimeException("TXT 文件读取失败", e);
        }
    }

    // =============================================
    // 格式检测
    // =============================================

    /**
     * 自动检测文档格式
     */
    DocumentFormat detectFormat(String content) {
        String[] lines = content.split("\n");
        int faqCount = 0;
        int convCount = 0;
        int articleCount = 0;
        int csvCount = 0;
        int totalSignificant = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("===")) continue;

            totalSignificant++;

            // FAQ 检测：Q: 或 Q：开头
            if (trimmed.startsWith("Q:") || trimmed.startsWith("Q：")) {
                faqCount++;
            }
            // 对话检测：User: 开头
            if (trimmed.startsWith("User:") || trimmed.startsWith("用户：") ||
                    trimmed.startsWith("顾客：") || trimmed.startsWith("客户：")) {
                convCount++;
            }
            // 文章检测：# 标题
            if (trimmed.startsWith("# ")) {
                articleCount++;
            }
            // CSV 检测：包含逗号和中文
            if (trimmed.contains(",") && containsChinese(trimmed)) {
                csvCount++;
            }
        }

        if (totalSignificant == 0) return DocumentFormat.PLAIN;

        // 计算各格式占比
        double faqRatio = (double) faqCount / totalSignificant;
        double convRatio = (double) convCount / totalSignificant;
        double articleRatio = (double) articleCount / totalSignificant;
        double csvRatio = (double) csvCount / totalSignificant;

        // 取最高占比的格式
        if (faqRatio >= FORMAT_DETECT_THRESHOLD) return DocumentFormat.FAQ;
        if (convRatio >= FORMAT_DETECT_THRESHOLD) return DocumentFormat.CONVERSATION;
        if (articleRatio >= FORMAT_DETECT_THRESHOLD) return DocumentFormat.ARTICLE;
        if (csvRatio >= FORMAT_DETECT_THRESHOLD) return DocumentFormat.CSV;

        // 多格式混合
        int highFormats = 0;
        if (faqRatio > 0.1) highFormats++;
        if (convRatio > 0.1) highFormats++;
        if (articleRatio > 0.1) highFormats++;
        if (csvRatio > 0.1) highFormats++;
        if (highFormats >= 2) return DocumentFormat.MIXED;

        return DocumentFormat.PLAIN;
    }

    // =============================================
    // FAQ 格式解析
    // =============================================

    /**
     * 解析 FAQ 格式：
     * Q: 问题
     * A: 回答
     * ---
     */
    String parseFaqFormat(String content) {
        StringBuilder result = new StringBuilder();
        // 按 --- 分隔段落
        String[] blocks = content.split("\n---\n|\\n---\\n");
        int entryNum = 0;

        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            String question = extractField(block, "Q[：:]");
            String answer = extractField(block, "A[：:]");

            if (question != null && answer != null) {
                entryNum++;
                result.append("FAQ-").append(entryNum).append(":\n");
                result.append("问题：").append(question).append("\n");
                result.append("回答：").append(answer).append("\n\n");
            }
        }

        if (entryNum == 0) {
            log.warn("FAQ 格式解析失败，未找到有效 Q/A 对，回退到纯文本");
            return content;
        }

        log.info("FAQ 解析完成: {} 条问答", entryNum);
        return result.toString();
    }

    // =============================================
    // 多轮对话格式解析
    // =============================================

    /**
     * 解析多轮对话格式：
     * User: ...
     * Assistant: ...
     * User: ...
     * Assistant: ...
     * ---
     *
     * 输出为结构化对话文本，便于语义检索
     */
    String parseConversationFormat(String content) {
        StringBuilder result = new StringBuilder();
        // 按 --- 分隔对话段落
        String[] blocks = content.split("\n---\n|\\n---\\n");
        int convNum = 0;

        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            String[] lines = block.split("\n");
            List<String> exchanges = new ArrayList<>();
            int turnNum = 0;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // 统一替换标记
                String normalized = normalizedLine(trimmed);
                if (normalized != null) {
                    exchanges.add(normalized);
                    turnNum++;
                }
            }

            if (turnNum >= 2) { // 至少 2 轮才算有效对话
                convNum++;
                result.append("对话-").append(convNum).append(":\n");
                for (String exchange : exchanges) {
                    result.append(exchange).append("\n");
                }
                result.append("\n");
            }
        }

        if (convNum == 0) {
            log.warn("对话格式解析失败，未找到有效对话，回退到纯文本");
            return content;
        }

        log.info("对话解析完成: {} 段对话", convNum);
        return result.toString();
    }

    /**
     * 统一对话行格式
     */
    private String normalizedLine(String line) {
        if (line.startsWith("User:") || line.startsWith("用户：") ||
                line.startsWith("顾客：") || line.startsWith("客户：")) {
            // 提取 User 后面的内容
            String content = line.substring(line.indexOf(":") + 1).trim();
            return "顾客：" + content;
        }
        if (line.startsWith("Assistant:") || line.startsWith("客服：") ||
                line.startsWith("助手：")) {
            String content = line.substring(line.indexOf(":") + 1).trim();
            return "客服：" + content;
        }
        // 不是对话行，返回 null
        return null;
    }

    // =============================================
    // 知识文章格式解析
    // =============================================

    /**
     * 解析知识文章格式：
     * # 标题
     * ## 类别: ...
     * ## 关键词: ...
     * 正文
     * ===
     *
     * 输出为带元数据的结构化文本
     */
    String parseArticleFormat(String content) {
        StringBuilder result = new StringBuilder();
        // 按 === 分隔文章
        String[] blocks = content.split("\n===\n|\\n===\\n");
        int articleNum = 0;

        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            // 提取标题
            String title = extractField(block, "# (.+)");
            String category = extractField(block, "## 类别[：:]\\s*(.+)");
            String keywords = extractField(block, "## 关键词[：:]\\s*(.+)");

            // 移除标题和元数据行，获取正文
            String body = removeMetadataLines(block);

            if (title != null && !body.isBlank()) {
                articleNum++;
                result.append("文章-").append(articleNum).append(":\n");
                result.append("标题：").append(title).append("\n");
                if (category != null) result.append("类别：").append(category).append("\n");
                if (keywords != null) result.append("关键词：").append(keywords).append("\n");
                result.append("内容：").append(body).append("\n\n");
            }
        }

        if (articleNum == 0) {
            log.warn("文章格式解析失败，未找到有效文章，回退到纯文本");
            return content;
        }

        log.info("文章解析完成: {} 篇文章", articleNum);
        return result.toString();
    }

    // =============================================
    // CSV 格式解析
    // =============================================

    /**
     * 解析 CSV 结构化格式：
     * question,answer,category
     * 问题,回答,类别
     */
    String parseCsvFormat(String content) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n");
        int csvNum = 0;
        boolean headerSkipped = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 跳过表头
            if (!headerSkipped && (trimmed.startsWith("question") || trimmed.startsWith("问题"))) {
                headerSkipped = true;
                continue;
            }
            headerSkipped = true;

            // 解析 CSV 行（支持逗号和中文逗号）
            String[] fields = trimmed.split("[，,]");
            if (fields.length >= 2) {
                String question = fields[0].trim();
                String answer = fields[1].trim();
                if (!question.isEmpty() && !answer.isEmpty()) {
                    csvNum++;
                    result.append("CSV-QA-").append(csvNum).append(":\n");
                    result.append("问题：").append(question).append("\n");
                    result.append("回答：").append(answer).append("\n");
                    if (fields.length >= 3) {
                        result.append("类别：").append(fields[2].trim()).append("\n");
                    }
                    result.append("\n");
                }
            }
        }

        if (csvNum == 0) {
            log.warn("CSV 格式解析失败，未找到有效记录，回退到纯文本");
            return content;
        }

        log.info("CSV 解析完成: {} 条记录", csvNum);
        return result.toString();
    }

    // =============================================
    // 混合格式解析
    // =============================================

    /**
     * 混合格式解析：按段落自动检测并分别处理
     * 一个文件中可能包含 FAQ + 对话 + 文章等多种格式
     */
    String parseMixedFormat(String content) {
        StringBuilder result = new StringBuilder();
        // 按双换行或分隔符分段
        String[] blocks = content.split("\n\n+|\n---\n|\\n===\\n");
        int totalParsed = 0;

        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty() || block.startsWith("===")) continue;

            // 对每个段落单独检测格式并解析
            DocumentFormat blockFormat = detectFormat(block);
            String parsed = switch (blockFormat) {
                case FAQ -> parseFaqFormat(block);
                case CONVERSATION -> parseConversationFormat(block);
                case ARTICLE -> parseArticleFormat(block);
                case CSV -> parseCsvFormat(block);
                default -> block;
            };

            // 统计解析出来的非空行数
            int linesInParsed = parsed.split("\n").length;
            if (linesInParsed > 2) {
                result.append(parsed).append("\n");
                totalParsed++;
            }
        }

        if (totalParsed == 0) {
            log.warn("混合格式解析失败，回退到纯文本");
            return content;
        }

        log.info("混合格式解析完成: {} 个段落", totalParsed);
        return result.toString();
    }

    // =============================================
    // 工具方法
    // =============================================

    /**
     * 读取输入流为字符串
     */
    private String readAll(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从文本中提取字段（正则匹配第一组）
     */
    private String extractField(String text, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 移除元数据行（# 标题和 ## 元数据），保留正文
     */
    private String removeMetadataLines(String text) {
        StringBuilder body = new StringBuilder();
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // 跳过标题行、元数据行、分隔符行
            if (trimmed.startsWith("# ") || trimmed.startsWith("## ") ||
                    trimmed.startsWith("===") || trimmed.isEmpty()) {
                continue;
            }
            body.append(trimmed).append(" ");
        }
        return body.toString().trim();
    }

    /**
     * 判断字符串是否包含中文字符
     */
    private boolean containsChinese(String text) {
        return text.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /**
     * 支持的文档格式枚举
     */
    enum DocumentFormat {
        FAQ,           // Q:/A: 问答格式
        CONVERSATION,  // User/Assistant 多轮对话
        ARTICLE,       // # 标题 + 正文知识文章
        CSV,           // 逗号分隔结构化数据
        MIXED,         // 多种格式混合
        PLAIN          // 纯文本（兜底）
    }
}