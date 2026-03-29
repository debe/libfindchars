package org.knownhosts.libfindchars.csv;

/**
 * Structural token types in CSV data.
 *
 * <p>Sealed interface with singleton record variants — enables exhaustive
 * pattern matching in switch expressions while keeping the lookup table
 * pattern via static constants.
 */
public sealed interface CsvToken {

    CsvToken QUOTE = new Quote();
    CsvToken DELIMITER = new Delimiter();
    CsvToken NEWLINE = new Newline();
    CsvToken CR = new Cr();

    record Quote() implements CsvToken {}
    record Delimiter() implements CsvToken {}
    record Newline() implements CsvToken {}
    record Cr() implements CsvToken {}
}
