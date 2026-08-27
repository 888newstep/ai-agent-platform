package com.aiagent.shared.data;

import java.util.Set;

/**
 * 训练数据清洗工具：文本归一化 + 无信息量样本过滤。
 *
 * 设计说明：
 * - normalize：trim、全角 ASCII 转半角、压缩连续空白
 * - isNoise：过滤寒暄 / 过短 / 纯符号等无信息量 QA 对，避免污染检索向量空间
 */
public final class DataCleaner {

    private DataCleaner() {
    }

    /** 归一化文本：全角 ASCII 转半角、trim、压缩空白（中文标点保留）。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** 判定一条 QA 对是否为无信息量噪音（寒暄/过短/纯符号）。 */
    public static boolean isNoise(String question, String answer) {
        String q = normalize(question);
        String a = normalize(answer);
        if (q.isEmpty() || a.isEmpty()) {
            return true;
        }
        // 答案过短（单字/空回复）
        if (a.length() < 2) {
            return true;
        }
        // 寒暄黑名单（归一化后精确匹配）
        if (CHITCHAT.contains(q) || CHITCHAT.contains(a)) {
            return true;
        }
        // 纯标点/符号
        if (isSymbolsOnly(q) || isSymbolsOnly(a)) {
            return true;
        }
        return false;
    }

    private static boolean isSymbolsOnly(String text) {
        int letters = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                letters++;
            }
        }
        return letters == 0;
    }

    /** 寒暄/无信息量黑名单（归一化后比较）。 */
    private static final Set<String> CHITCHAT = Set.of(
            "谢谢", "好的", "嗯", "哦", "哦哦", "嗯嗯", "收到", "不客气", "明白了",
            "知道了", "好的谢谢", "好", "嗯好", "好嘞", "哈哈", "呵呵", "ok", "okay",
            "嗯好的", "亲", "好的呢", "谢谢亲", "麻烦", "好的哦", "行", "嗯好嘞",
            "谢谢哦", "好哒", "明白了哦"
    );
}
