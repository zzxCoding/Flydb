package com.flydb.cli.output.json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 手写的最小 JSON 生成器（设计 10 机器契约）。
 *
 * <p>只做输出不做解析：按写入顺序生成紧凑单行 JSON，字符串转义遵循 RFC 8259
 * （引号、反斜杠、控制字符；非 ASCII 字符原样保留，由外层以 UTF-8 打印）。
 * 不引入第三方依赖，flydb-cli 发行包依赖面保持不变。
 */
public final class JsonWriter {

    private final StringBuilder output = new StringBuilder();
    /** 每层已打开的容器是否已有元素，用于决定下一个元素前是否输出逗号。 */
    private final Deque<Boolean> elementStarted = new ArrayDeque<Boolean>();
    /** 刚写入字段名、等待字段值的状态；此状态下输出值不加逗号。 */
    private boolean pendingName;

    public JsonWriter beginObject() {
        beforeValue();
        output.append('{');
        elementStarted.push(Boolean.FALSE);
        return this;
    }

    public JsonWriter endObject() {
        output.append('}');
        elementStarted.pop();
        return this;
    }

    public JsonWriter beginArray() {
        beforeValue();
        output.append('[');
        elementStarted.push(Boolean.FALSE);
        return this;
    }

    public JsonWriter endArray() {
        output.append(']');
        elementStarted.pop();
        return this;
    }

    /** 对象字段名；只能紧跟 {@link #beginObject()} 之后、字段值之前。 */
    public JsonWriter name(String name) {
        if (pendingName) {
            throw new IllegalStateException("字段名之后缺少字段值: " + name);
        }
        if (!elementStarted.isEmpty() && elementStarted.pop()) {
            output.append(',');
        }
        elementStarted.push(Boolean.TRUE);
        writeString(name);
        output.append(':');
        pendingName = true;
        return this;
    }

    public JsonWriter value(String value) {
        beforeValue();
        if (value == null) output.append("null");
        else writeString(value);
        return this;
    }

    public JsonWriter value(long value) {
        beforeValue();
        output.append(value);
        return this;
    }

    /** 可空数值（checksum、耗时等）：null 序列化为 JSON null，否则按整型输出。 */
    public JsonWriter value(Number value) {
        beforeValue();
        if (value == null) output.append("null");
        else output.append(value.longValue());
        return this;
    }

    public JsonWriter value(boolean value) {
        beforeValue();
        output.append(value);
        return this;
    }

    private void beforeValue() {
        if (pendingName) {
            pendingName = false;
            return;
        }
        if (!elementStarted.isEmpty()) {
            if (elementStarted.pop()) output.append(',');
            elementStarted.push(Boolean.TRUE);
        }
    }

    private void writeString(String value) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (ch < 0x20) output.append(String.format("\\u%04x", (int) ch));
                    else output.append(ch);
            }
        }
        output.append('"');
    }

    @Override public String toString() {
        return output.toString();
    }
}
