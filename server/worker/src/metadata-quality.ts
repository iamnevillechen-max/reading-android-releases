/** Text metadata must never expose HTML or detached image attributes to readers. */
export const BOOK_METADATA_VERSION = 2;

export function hasMetadataMarkup(value: unknown): boolean {
  if (typeof value !== 'string') return true;
  return /<\/?[a-z][^>]*>|&lt;\/?[a-z]|[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/i.test(value) ||
    (value.includes('/>') && /(?:^|[\s"'])(?:width|height|alt|src|href|class)\s*=/i.test(value));
}

export function usableBookMetadata(value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const book = value as Record<string, unknown>;
  return typeof book.name === 'string' && book.name.trim().length > 0 && book.name.length <= 1024 &&
    !hasMetadataMarkup(book.name) && (book.author == null ||
      (typeof book.author === 'string' && book.author.length <= 1024 && !hasMetadataMarkup(book.author)));
}

export class BookMetadataError extends Error {
  readonly code = 'BOOK_METADATA_INVALID';
  constructor() { super('The source did not return plain-text book metadata'); }
}

export function requireBookMetadata(book: unknown): void {
  if (!usableBookMetadata(book)) throw new BookMetadataError();
}

export function usableBookResult(operation: string, value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const result = value as Record<string, unknown>;
  if (operation === 'search' || operation === 'explore') {
    return Array.isArray(result.books) && result.books.every(usableBookMetadata);
  }
  return result.book === undefined ? operation === 'content' : usableBookMetadata(result.book);
}
