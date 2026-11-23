import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import {
  decodeCallHistoryKey,
  persistEncryptedCallRecord,
  listEncryptedCallRecords,
  CALL_HISTORY_RETENTION_DAYS
} from '../call-history-store.js';

function fakeCollection() {
  const rows = new Map();
  return {
    rows,
    doc(id) {
      return { id, set: async value => rows.set(id, value) };
    },
    orderBy() {
      return { limit: count: undefined };
    }
  };
}
