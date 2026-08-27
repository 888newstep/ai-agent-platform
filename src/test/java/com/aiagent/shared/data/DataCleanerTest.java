package com.aiagent.shared.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataCleanerTest {

    @Test
    void normalizesText() {
        // 换行压缩为空格、全角问号转半角、trim
        assertThat(DataCleaner.normalize("  如何\n 退款？  ")).isEqualTo("如何 退款?");
        assertThat(DataCleaner.normalize("ＡＢＣ１２３")).isEqualTo("ABC123");
        assertThat(DataCleaner.normalize(null)).isEmpty();
        assertThat(DataCleaner.normalize("  好的  谢谢  ")).isEqualTo("好的 谢谢");
    }

    @Test
    void convertsFullWidthPunctuationToHalfWidth() {
        // 全角标点统一转半角，消除书写变体，利于检索匹配
        assertThat(DataCleaner.normalize("多少钱，包邮吗？")).isEqualTo("多少钱,包邮吗?");
        assertThat(DataCleaner.normalize("全角：测试；括号（）")).isEqualTo("全角:测试;括号()");
    }

    @Test
    void flagsChitchatAsNoise() {
        assertThat(DataCleaner.isNoise("好的", "不客气哦")).isTrue();
        assertThat(DataCleaner.isNoise("谢谢", "亲")).isTrue();
        assertThat(DataCleaner.isNoise("嗯", "嗯")).isTrue();
    }

    @Test
    void flagsEmptyOrTooShortAsNoise() {
        assertThat(DataCleaner.isNoise("", "回复")).isTrue();
        assertThat(DataCleaner.isNoise("问题", "")).isTrue();
        assertThat(DataCleaner.isNoise("问题", "好")).isTrue();
    }

    @Test
    void flagsSymbolsOnlyAsNoise() {
        assertThat(DataCleaner.isNoise("？？？", "？？？")).isTrue();
    }

    @Test
    void keepsNormalQa() {
        assertThat(DataCleaner.isNoise("申通能放几天", "30天的保质期呢")).isFalse();
        assertThat(DataCleaner.isNoise("怎么退款", "提交退款申请即可")).isFalse();
    }
}
