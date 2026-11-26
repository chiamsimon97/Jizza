package com.jizza.engine.api.finance;

import java.util.Optional;
import java.util.UUID;

/**
 * The statement files Kai imports (bank CSVs, CAS PDFs, broker exports): kept in the user's
 * {@code files} rows, encrypted at rest like every other user file, and read back only by their
 * owner. Also turns a PDF into text, with its password held in memory for that one call.
 */
public interface StatementFiles {

  /** Stores the bytes for the owner and returns the file id. */
  UUID store(UUID userId, String filename, String contentType, byte[] bytes);

  /** The bytes of one of the owner's statement files; empty for anyone else's. */
  Optional<byte[]> read(UUID userId, UUID fileId);

  /** Removes one of the owner's statement files and its stored bytes; nothing for anyone else's. */
  void delete(UUID userId, UUID fileId);

  /**
   * The text of a PDF, page by page in reading order. The password is used for this call only and
   * is never stored or logged.
   *
   * @throws PdfLockedException when the PDF needs a password and none, or the wrong one, was given
   * @throws com.jizza.engine.api.error.ValidationException when the bytes are not a readable PDF
   */
  String pdfText(byte[] bytes, String password);

  /** A PDF that needs a password Kai does not have. */
  class PdfLockedException extends com.jizza.engine.api.error.ValidationException {
    private final boolean passwordGiven;

    public PdfLockedException(boolean passwordGiven) {
      super(passwordGiven
          ? "That password did not open the PDF. Check it and try again."
          : "This PDF is locked. Enter its password to read it.");
      this.passwordGiven = passwordGiven;
    }

    public boolean passwordGiven() {
      return passwordGiven;
    }
  }
}
