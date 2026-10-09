import { describe, expect, it } from "vitest";
import { env } from "cloudflare:workers";
import { call, newAccount, pairDevice } from "./helpers.js";

/** Compte admin : ADMIN_LOGINS = "khalilbenaz" (wrangler.toml), créé via le parcours d'inscription normal. */
async function adminAccount() { return newAccount("khalilbenaz"); }

describe("administration", () => {
  it("compte ordinaire : /admin et l'export répondent 404 (existence non révélée), pas de lien dans le tableau de bord", async () => {
    const a = await newAccount();
    expect((await call("/admin", { cookie: a.cookie, ip: a.ip })).status).toBe(404);
    expect((await call("/admin/comptes.csv", { cookie: a.cookie, ip: a.ip })).status).toBe(404);
    expect(await (await call("/", { cookie: a.cookie, ip: a.ip })).text()).not.toContain('href="/admin"');
  });
  it("sans session : renvoyé à la connexion", async () => {
    expect((await call("/admin")).headers.get("location")).toBe("/login");
  });
  it("admin : liste des comptes et appareils, aucun secret, export CSV neutralisé", async () => {
    const other = await newAccount();
    await pairDevice(other, "=calc Salon");
    const admin = await adminAccount();
    expect(await (await call("/", { cookie: admin.cookie, ip: admin.ip })).text()).toContain('href="/admin"');
    const page = await (await call("/admin", { cookie: admin.cookie, ip: admin.ip })).text();
    expect(page).toContain(other.login);
    expect(page).toContain("=calc Salon");
    expect(page).not.toMatch(/passwordHash|providersEnc|traktEnc|osEnc/);
    const csv = await call("/admin/comptes.csv", { cookie: admin.cookie, ip: admin.ip });
    expect(csv.headers.get("content-type")).toContain("text/csv");
    const text = await csv.text();
    // Cellule commençant par « = » neutralisée (formule) dans Excel / Numbers.
    expect(text).toMatch(/"'=calc Salon \(/);
    expect(text).not.toMatch(/pbkdf2|v1\.[0-9a-f]{8}\./);
  });
  it("pays de l'inscription gardé (code à 2 lettres), jamais l'adresse IP", async () => {
    const a = await newAccount();
    const acct = JSON.parse(await env.CONFIG.get(`acct:${a.login}`));
    expect(JSON.stringify(acct)).not.toContain(a.ip);
    expect(acct.country === null || /^[A-Z]{2}$/.test(acct.country)).toBe(true);
  });
});
