package com.aiagent.ecommerce.application;

import com.aiagent.ecommerce.config.EcommerceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QaClassifierTest {

    private QaClassifier classifier;

    @BeforeEach
    void setUp() {
        EcommerceProperties props = new EcommerceProperties();
        EcommerceProperties.Classifier cfg = new EcommerceProperties.Classifier();
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("shipping_logistics", List.of("快递", "发货", "物流", "申通"));
        keywords.put("refund_return", List.of("退款", "退货", "退钱"));
        keywords.put("order_payment", List.of("付款", "下单", "订单", "抬头"));
        keywords.put("promotion_price", List.of("包邮", "优惠", "券", "价格"));
        keywords.put("product_specification", List.of("规格", "尺寸", "斤", "袋"));
        keywords.put("after_sales_quality", List.of("坏", "裂", "破", "质量"));
        cfg.setKeywords(keywords);
        props.getImportConfig().setClassifier(cfg);
        classifier = new QaClassifier(props);
    }

    @Test
    void classifiesByQuestionKeyword() {
        assertThat(classifier.classify("申通能放几天", "30天")).isEqualTo("shipping_logistics");
        assertThat(classifier.classify("怎么退款", "提交申请")).isEqualTo("refund_return");
        assertThat(classifier.classify("五斤分几袋包装", "分成几袋")).isEqualTo("product_specification");
        assertThat(classifier.classify("包邮吗", "包邮的")).isEqualTo("promotion_price");
    }

    @Test
    void questionOutweighsAnswer() {
        // question 命中 refund_return(退款)，answer 命中 shipping(发货)，question 权重高
        assertThat(classifier.classify("我要退款", "快递已经发出了")).isEqualTo("refund_return");
    }

    @Test
    void fallsBackToOtherWhenNoKeywordHits() {
        assertThat(classifier.classify("随便聊聊", "好的")).isEqualTo("other");
    }

    @Test
    void disabledClassifierReturnsOther() {
        EcommerceProperties props = new EcommerceProperties();
        props.getImportConfig().getClassifier().setEnabled(false);
        QaClassifier disabled = new QaClassifier(props);
        assertThat(disabled.classify("申通能放几天", "30天")).isEqualTo("other");
    }
}
