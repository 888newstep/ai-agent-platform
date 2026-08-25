package com.aiagent.shared.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingQaParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesAndNormalizesTrainingMessages() throws Exception {
        TrainingQaParser.TrainingQa record = TrainingQaParser.parse(objectMapper, """
                {"messages":[
                  {"role":"system","content":"客服规则"},
                  {"role":"user","content":"  如何\\n 退款？ "},
                  {"role":"assistant","content":" 提交   退款申请 "}
                ]}
                """).orElseThrow();

        assertThat(record.systemPrompt()).isEqualTo("客服规则");
        assertThat(record.question()).isEqualTo("如何 退款？");
        assertThat(record.answer()).isEqualTo("提交 退款申请");
        assertThat(record.embeddingText()).isEqualTo("用户问题：如何 退款？ 客服回答：提交 退款申请");
        assertThat(record.isComplete()).isTrue();
    }

    @Test
    void rejectsRecordsWithoutTheRequiredMessageShape() throws Exception {
        assertThat(TrainingQaParser.parse(objectMapper, """
                {"messages":[{"role":"user","content":"问题"}]}
                """)).isEmpty();
        assertThat(TrainingQaParser.parse(objectMapper, "{}"))
                .isEmpty();
    }
}
