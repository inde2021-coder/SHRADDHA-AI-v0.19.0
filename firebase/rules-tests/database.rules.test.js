import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment
} from "@firebase/rules-unit-testing";
import { get, ref, set } from "firebase/database";

const rules = readFileSync(new URL("../database.rules.json", import.meta.url), "utf8");
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-shraddha-ai",
    database: { host: "127.0.0.1", port: 9000, rules }
  });
});

beforeEach(async () => env.clearDatabase());
after(async () => env.cleanup());

const record = {
  format: "AES-256-GCM/PBKDF2-SHA256-v1",
  payload: "ciphertext-only",
  updatedAt: Date.now()
};

test("authenticated owner can write and read their encrypted vault record", async () => {
  const db = env.authenticatedContext("user-a").database();
  const vault = ref(db, "users/user-a/vault/memory-1");
  await assertSucceeds(set(vault, record));
  const snapshot = await assertSucceeds(get(vault));
  if (snapshot.val()?.payload !== record.payload) throw new Error("Vault payload did not round-trip");
});

test("another authenticated UID cannot read or write the owner's vault", async () => {
  const ownerDb = env.authenticatedContext("user-a").database();
  await assertSucceeds(set(ref(ownerDb, "users/user-a/vault/memory-1"), record));
  const otherDb = env.authenticatedContext("user-b").database();
  await assertFails(get(ref(otherDb, "users/user-a/vault/memory-1")));
  await assertFails(set(ref(otherDb, "users/user-a/vault/stolen"), record));
});

test("unauthenticated clients cannot read or write vault data", async () => {
  const db = env.unauthenticatedContext().database();
  const vault = ref(db, "users/user-a/vault/memory-1");
  await assertFails(get(vault));
  await assertFails(set(vault, record));
});

test("vault rejects unexpected fields and unsupported encryption formats", async () => {
  const db = env.authenticatedContext("user-a").database();
  await assertFails(set(ref(db, "users/user-a/vault/extra"), { ...record, plaintext: "secret" }));
  await assertFails(set(ref(db, "users/user-a/vault/bad-format"), { ...record, format: "plain-text" }));
});

test("vault rejects payloads larger than the configured limit", async () => {
  const db = env.authenticatedContext("user-a").database();
  await assertFails(set(ref(db, "users/user-a/vault/too-large"), { ...record, payload: "x".repeat(190001) }));
});
