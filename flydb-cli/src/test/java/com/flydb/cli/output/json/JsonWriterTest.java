package com.flydb.cli.output.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonWriter")
class JsonWriterTest {

    @Test
    @DisplayName("字符串值转义引号、反斜杠与控制字符")
    void escapesStrings() {
        String json = new JsonWriter().beginObject()
                .name("quote").value("a\"b")
                .name("backslash").value("C:\\dir")
                .name("newline").value("first\nsecond")
                .name("tab").value("a\tb")
                .name("control").value("a\u0001b")
                .endObject().toString();

        assertThat(json).isEqualTo("{\"quote\":\"a\\\"b\",\"backslash\":\"C:\\\\dir\","
                + "\"newline\":\"first\\nsecond\",\"tab\":\"a\\tb\",\"control\":\"a\\u0001b\"}");
    }

    @Test
    @DisplayName("中文字符原样保留，数组混合标量类型")
    void writesArrayScalars() {
        String json = new JsonWriter().beginArray()
                .value("待执行")
                .value(42)
                .value(true)
                .value((String) null)
                .value(Integer.valueOf(7))
                .endArray().toString();

        assertThat(json).isEqualTo("[\"待执行\",42,true,null,7]");
    }

    @Test
    @DisplayName("嵌套容器与空容器正确处理逗号")
    void writesNestedContainers() {
        String json = new JsonWriter().beginObject()
                .name("emptyArray").beginArray().endArray()
                .name("outer").beginObject()
                .name("first").beginArray().value(1).value(2).endArray()
                .name("second").beginObject().name("leaf").value("x").endObject()
                .endObject()
                .endObject().toString();

        assertThat(json).isEqualTo("{\"emptyArray\":[],\"outer\":{\"first\":[1,2],"
                + "\"second\":{\"leaf\":\"x\"}}}");
    }
}
