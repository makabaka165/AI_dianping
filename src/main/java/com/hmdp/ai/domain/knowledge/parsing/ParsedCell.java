package com.hmdp.ai.domain.knowledge.parsing;

public final class ParsedCell {
    private final int row;
    private final int column;
    private final String address;
    private final String value;
    public ParsedCell(int row, int column, String address, String value) {
        this.row = row; this.column = column; this.address = address; this.value = value;
    }
    public int getRow() { return row; }
    public int getColumn() { return column; }
    public String getAddress() { return address; }
    public String getValue() { return value; }
}
