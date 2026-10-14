package ee.shop.catalog.service;

import ee.shop.catalog.web.dto.AdminProductRequest;
import ee.shop.common.error.AppException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bulk product import, JSON or CSV.
 *
 * ALL-OR-NOTHING, deliberately. Every row is validated before ANY row is
 * written, and a single bad row aborts the whole import with a report of
 * every problem found — not just the first.
 *
 * Two reasons, and both come from what a half-import actually costs:
 *  - A catalogue where rows 1-40 imported and 41-200 didn't is worse than one
 *    that didn't import at all, because nobody can tell by looking which
 *    state it is in, and re-running the file then duplicates the first 40.
 *  - Reporting one error per attempt turns fixing a 200-row spreadsheet into
 *    200 upload cycles.
 *
 * The transaction is what makes the first guarantee real: validation happens
 * first, but the writes still roll back together if anything throws later
 * (an unexpected constraint, a slug that collided with a concurrent create).
 */
@Service
public class ProductImportService {

    /** Bounded so a malicious or mistaken upload can't try to write a million rows in one transaction. */
    private static final int MAX_ROWS = 1000;

    private final AdminCatalogService catalog;
    private final Validator validator;

    public ProductImportService(AdminCatalogService catalog, Validator validator) {
        this.catalog = catalog;
        this.validator = validator;
    }

    public record RowError(int row, String field, String message) {}

    public record ImportResult(int imported, List<UUID> productIds) {}

    @Transactional
    public ImportResult importAll(List<AdminProductRequest> rows) {
        if (rows == null || rows.isEmpty()) {
            throw AppException.badRequest("Nothing to import");
        }
        if (rows.size() > MAX_ROWS) {
            throw AppException.badRequest("Too many rows: " + rows.size() + " (limit " + MAX_ROWS + ")");
        }

        // Pass 1: validate EVERYTHING, collect every problem.
        List<RowError> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            AdminProductRequest row = rows.get(i);
            if (row == null) {
                errors.add(new RowError(i + 1, null, "row is empty"));
                continue;
            }
            for (ConstraintViolation<AdminProductRequest> v : validator.validate(row)) {
                errors.add(new RowError(i + 1, v.getPropertyPath().toString(), v.getMessage()));
            }
        }
        // Duplicate slugs WITHIN the file — the database's unique index would
        // catch this, but only on row N, after N-1 rows had already been
        // written and rolled back, and with a message naming neither row.
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                if (rows.get(i) != null && rows.get(j) != null
                        && rows.get(i).slug() != null
                        && rows.get(i).slug().equals(rows.get(j).slug())) {
                    errors.add(new RowError(j + 1, "slug",
                            "duplicate of row " + (i + 1) + " within this file"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ImportFailedException(errors);
        }

        // Pass 2: write. Any failure here rolls the whole batch back.
        List<UUID> created = new ArrayList<>();
        for (AdminProductRequest row : rows) {
            created.add(catalog.createProduct(row).getId());
        }
        return new ImportResult(created.size(), created);
    }

    /**
     * Column order is irrelevant — rows are read by HEADER NAME, so a
     * spreadsheet exported with columns in a different order still imports.
     * Header names match the JSON field names exactly, so one documented
     * shape covers both upload formats.
     */
    public List<AdminProductRequest> parseCsv(InputStream in) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();

        List<AdminProductRequest> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), format)) {
            for (CSVRecord record : parser) {
                rows.add(new AdminProductRequest(
                        uuidOrNull(record, "categoryId"),
                        get(record, "name"),
                        get(record, "slug"),
                        get(record, "description"),
                        get(record, "brand"),
                        decimalOrNull(record, "price"),
                        get(record, "currency"),
                        get(record, "vatCategory"),
                        intOrZero(record, "stockQuantity"),
                        decimalOrNull(record, "weightKg"),
                        decimalOrNull(record, "lengthCm"),
                        decimalOrNull(record, "widthCm"),
                        decimalOrNull(record, "heightCm"),
                        boolOrFalse(record, "featured")));
            }
        } catch (IOException e) {
            throw AppException.badRequest("Could not read the CSV file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // Commons CSV throws this for a missing/duplicated header column.
            throw AppException.badRequest("CSV header problem: " + e.getMessage());
        }
        return rows;
    }

    private static String get(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value;
    }

    private static BigDecimal decimalOrNull(CSVRecord record, String column) {
        String raw = get(record, column);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            // Surfaced as a row error rather than a 500 — a spreadsheet with
            // "12,50" instead of "12.50" is a user mistake, not a server fault.
            throw new ImportFailedException(List.of(new RowError(
                    (int) record.getRecordNumber(), column, "'" + raw + "' is not a number")));
        }
    }

    private static int intOrZero(CSVRecord record, String column) {
        String raw = get(record, column);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ImportFailedException(List.of(new RowError(
                    (int) record.getRecordNumber(), column, "'" + raw + "' is not a whole number")));
        }
    }

    /**
     * An omitted "featured" column means false — importing a price list must
     * not quietly promote the whole upload onto the home page.
     *
     * Accepts the spellings a spreadsheet actually produces (true/false,
     * yes/no, 1/0, TRUE/FALSE) rather than only Java's Boolean.parseBoolean,
     * which silently maps every unrecognised string — "yes" included — to
     * false. A value that is present but unrecognised is an error, not a
     * false: the importer's contract everywhere else is that bad input is
     * reported per row, never guessed at.
     */
    private static boolean boolOrFalse(CSVRecord record, String column) {
        String raw = get(record, column);
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true", "yes", "y", "1" -> true;
            case "false", "no", "n", "0" -> false;
            default -> throw new ImportFailedException(List.of(new RowError(
                    (int) record.getRecordNumber(), column,
                    "'" + raw + "' is not true/false")));
        };
    }

    private static UUID uuidOrNull(CSVRecord record, String column) {
        String raw = get(record, column);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ImportFailedException(List.of(new RowError(
                    (int) record.getRecordNumber(), column, "'" + raw + "' is not a valid id")));
        }
    }

    /** Carries the full error list so the caller can hand back every problem at once. */
    public static class ImportFailedException extends RuntimeException {
        private final transient List<RowError> errors;

        public ImportFailedException(List<RowError> errors) {
            super("Import rejected: " + errors.size() + " problem(s)");
            this.errors = errors;
        }

        public List<RowError> errors() {
            return errors;
        }
    }
}
