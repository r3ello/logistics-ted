package com.bellgado.logistics_ted.service.exporter;

import java.util.List;

/**
 * Everything the CSV export needs to know about one dataset. Adding an exportable dataset means
 * writing one of these (a {@code @Component}) and nothing else — the controller, the column
 * selection, the CSV rendering, the audit trail and the {@code /api/export/entities} catalogue are
 * all generic over this interface, so the catalogue cannot describe a dataset that does not exist.
 *
 * <p><b>Implementations are {@code @Component}, never {@code @Service}.</b> {@code
 * ServiceLoggingAspect} renders the arguments and results of {@code @Service} calls at DEBUG, and
 * a dataset's rows are exactly the bulk personal data that must not end up in a log.
 *
 * <p><b>{@link #columns()} is the contract of what may leave the system.</b> A field absent from it
 * cannot be exported by any request — which is how, for attendance, the device fingerprint and the
 * check-in GPS coordinates are kept out.
 *
 * @param <R> the row type the columns extract from
 */
public interface EntityExporter<R> {

    /** URL segment and file-name stem, kebab-case: {@code attendance-sessions}. */
    String name();

    /** One sentence for the catalogue: what one row is. */
    String description();

    /** Every exportable column, in the default order. */
    List<ExportColumn<R>> columns();

    /** The filters the dataset accepts. */
    List<ExportFilter> filters();

    /**
     * The rows matching the query, in export order.
     *
     * @throws ExportException when the filters are valid individually but not together
     *                         (e.g. a date range that is backwards or too wide)
     */
    List<R> fetch(ExportQuery query);

    /** Suggested download name. */
    default String fileName(ExportQuery query) {
        return name() + ".csv";
    }
}
