import fs from "node:fs";
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, setDoc } from "firebase/firestore";
import { ref, uploadBytes, getBytes } from "firebase/storage";
import { ref as databaseRef, set as databaseSet, get as databaseGet } from "firebase/database";

const projectId = "demo-shraddha-ai";
const format = "AES-256-GCM/PBKDF2-SHA256-v1";
const readRules = relativePath => fs.readFileSync(new URL(relativePath, import.meta.url), "utf8");
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: { rules: readRules("../firestore.rules") },
    storage: { rules: readRules("../storage.rules") },
    database: { rules: readRules("../database.rules.json") }
  });
});

after(async () => {
  await env?.cleanup();
});

test("Firestore vault is owner-only and rejects malformed payloads", async () => {
  const alice = env.authenticatedContext("alice").firestore();
  const bob = env.authenticatedContext("bob").firestore();
  const path = doc(alice, "users/alice/vault/personal_memory");
  await assertSucceeds(setDoc(path, { format, payload: "ciphertext", updatedAt: Date.now() }));
  await assertSucceeds(getDoc(path));
  await assertFails(getDoc(doc(bob, "users/alice/vault/personal_memory")));
  await assertFails(setDoc(doc(alice, "users/alice/vault/invalid"), {
    format, payload: "ciphertext", updatedAt: Date.now(), plaintext: "must-not-be-accepted"
  }));
  await assertFails(setDoc(doc(alice, "users/bob/vault/personal_memory"), {
    format, payload: "ciphertext", updatedAt: Date.now()
  }));
});

test("Firestore Storage metadata and FCM device records are scoped to the owner", async () => {
  const alice = env.authenticatedContext("alice").firestore();
  const bob = env.authenticatedContext("bob").firestore();
  const deviceId = "a".repeat(64);
  await assertSucceeds(setDoc(doc(alice, `users/alice/devices/${deviceId}`), {
    fcmToken: "f".repeat(32), updatedAt: Date.now(), platform: "android", deviceName: "Test phone"
  }));
  await assertFails(getDoc(doc(bob, `users/alice/devices/${deviceId}`)));
  await assertFails(setDoc(doc(alice, "users/alice/devices/not-a-hash"), {
    fcmToken: "f".repeat(32), updatedAt: Date.now(), platform: "android", deviceName: "Test phone"
  }));
  await assertSucceeds(setDoc(doc(alice, "users/alice/storage_backups/backup_1770000000000_deadbeef"), {
    storagePath: "users/alice/backups/backup_1770000000000_deadbeef.enc",
    format, updatedAt: Date.now(), sizeBytes: 128
  }));
  await assertFails(setDoc(doc(alice, "users/alice/storage_backups/backup_1770000000000_deadbeef"), {
    storagePath: "users/bob/backups/backup_1770000000000_deadbeef.enc",
    format, updatedAt: Date.now(), sizeBytes: 128
  }));
});

test("Realtime Database encrypted vault is owner-only and rejects plaintext", async () => {
  const alice = env.authenticatedContext("alice").database();
  const bob = env.authenticatedContext("bob").database();
  const path = "users/alice/vault/personal_memory";
  const encryptedRecord = { format, payload: "ciphertext", updatedAt: Date.now() };
  await assertSucceeds(databaseSet(databaseRef(alice, path), encryptedRecord));
  await assertSucceeds(databaseGet(databaseRef(alice, path)));
  await assertFails(databaseGet(databaseRef(bob, path)));
  await assertFails(databaseSet(databaseRef(alice, "users/bob/vault/personal_memory"), encryptedRecord));
  await assertFails(databaseSet(databaseRef(alice, "users/alice/vault/plaintext"), {
    format, payload: "ciphertext", updatedAt: Date.now(), plaintext: "must-not-be-accepted"
  }));
  await assertFails(databaseSet(databaseRef(alice, "users/alice/vault/invalid key"), encryptedRecord));
});

test("Firestore vault deletion is limited to the explicitly supported backup keys", async () => {
  const alice = env.authenticatedContext("alice").firestore();
  await assertSucceeds(setDoc(doc(alice, "users/alice/vault/personal_memory"), {
    format, payload: "ciphertext", updatedAt: Date.now()
  }));
  await assertFails(deleteDoc(doc(alice, "users/alice/vault/personal_memory")));
  await assertSucceeds(setDoc(doc(alice, "users/alice/vault/selected_device_backup_v1"), {
    format, payload: "ciphertext", updatedAt: Date.now()
  }));
  await assertSucceeds(deleteDoc(doc(alice, "users/alice/vault/selected_device_backup_v1")));
});

test("Firebase Storage accepts only owner-scoped encrypted backup objects", async () => {
  const alice = env.authenticatedContext("alice").storage();
  const bob = env.authenticatedContext("bob").storage();
  const path = "users/alice/backups/backup_1770000000000_deadbeef.enc";
  const metadata = {
    contentType: "application/octet-stream",
    customMetadata: { format, updatedAt: String(Date.now()) }
  };
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const uploaded = await assertSucceeds(uploadBytes(ref(alice, path), bytes, metadata));
  assert.equal(uploaded.metadata.size, 4);
  await assertSucceeds(getBytes(ref(alice, path), 16));
  await assertFails(getBytes(ref(bob, path), 16));
  await assertFails(uploadBytes(ref(alice, "users/bob/backups/backup_1770000000000_deadbeef.enc"), bytes, metadata));
  await assertFails(uploadBytes(ref(alice, "users/alice/backups/not-encrypted.txt"), bytes, metadata));
  await assertFails(uploadBytes(ref(alice, "users/alice/backups/backup_1770000000000_deadbeef.enc"), bytes, {
    contentType: "text/plain", customMetadata: { format: "plaintext", updatedAt: String(Date.now()) }
  }));
  await assertFails(uploadBytes(ref(alice, "users/alice/backups/backup_1770000000001_deadbeef.enc"), bytes, {
    contentType: "application/octet-stream", customMetadata: { format: "plaintext", updatedAt: String(Date.now()) }
  }));
  await assertFails(uploadBytes(ref(alice, "users/alice/documents/private.pdf"), bytes, {
    contentType: "application/pdf"
  }));
});
