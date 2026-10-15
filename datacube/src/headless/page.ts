// A PAGE DOCUMENT READ AND WRITTEN AS DATACUBE READS AND WRITES ONE, HEADLESS (docs/PYTHON_ASKS_DATACUBE_2026_10_15.md,
// item 1): what Python's pages ask of page-document.ts when one is loaded, read back from the open page, given a sheet's
// layout, saved or written out -- a page of any version DataCube reads, migrated, every one of its rules held
// (readPage), written as DataCube writes it (pageToJson) -- so Python checks and writes no page by rules of its own.

import { CubeDocumentError } from '../cube-document.ts';
import { PageDocumentError, pageToJson, readPage } from '../page-document.ts';

/**
 * A page document (JSON text) as DataCube reads it and would write it again. JSON: `{page}`, the page's text as
 * DataCube writes it (its numbers exact), or `{problems}`, why DataCube does not read it, in its words.
 */
export function pageRead(text: string): string {
  try {
    return JSON.stringify({ page: pageToJson(readPage(text)) });
  } catch (e) {
    if (!(e instanceof PageDocumentError) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}
