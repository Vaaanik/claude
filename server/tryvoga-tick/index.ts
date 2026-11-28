// Сервер приложения «Тревога и свет».
//
// POST (по расписанию, раз в минуту): ~50 секунд опрашивает Telegram-каналы
// каждые 10 секунд и рассылает новые сообщения на телефоны через Firebase
// (тема "posts"). Разбор сообщений и прогноз делает само приложение.
// GET: отдаёт состояние — когда была последняя проверка и последние номера
// сообщений, чтобы приложение могло догнать пропущенное.
import postgres from "npm:postgres@3.4.5";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 2 });

const ROUNDS = 5;            // проверок за один запуск
const EVERY_MS = 10_000;     // пауза между проверками
const HEARTBEAT_MS = 10 * 60_000;

type Post = { id: number; ts: number; text: string; reply: string };
type Channel = { name: string; last_id: number; filter: string | null; max_len: number };

function plain(html: string): string {
  return html
    .replace(/<br\s*\/?>/g, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&#33;/g, "!").replace(/&#39;/g, "'").replace(/&#036;/g, "$")
    .replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&")
    .replace(/[ \t]+/g, " ").trim();
}

function parse(html: string): Post[] {
  const out: Post[] = [];
  for (const b of html.split("tgme_widget_message_wrap").slice(1)) {
    const id = b.match(/data-post="[^"\/]+\/(\d+)"/);
    if (!id) continue;
    const text = b.match(/tgme_widget_message_text js-message_text[^>]*>([\s\S]*?)<\/div>/);
    const reply = b.match(/js-message_reply_text[^>]*>([\s\S]*?)<\/div>/);
    const time = b.match(/<time datetime="([^"]+)" class="time"/);
    out.push({
      id: Number(id[1]),
      ts: time ? Date.parse(time[1]) : Date.now(),
      text: text ? plain(text[1]) : "",
      reply: reply ? plain(reply[1]) : "",
    });
  }
  return out.sort((a, b) => a.id - b.id);
}

async function fetchChannel(ch: Channel): Promise<Post[]> {
  const url = `https://t.me/s/${ch.name}` + (ch.last_id > 0 ? `?after=${ch.last_id}` : "");
  const r = await fetch(url, {
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 14) Mobile" },
    signal: AbortSignal.timeout(7000),
  });
  if (!r.ok) throw new Error(`${ch.name}: HTTP ${r.status}`);
  return parse(await r.text());
}

// ---------------------------------------------------------------- Firebase

let token: { value: string; until: number } | null = null;
let account: { project_id: string; client_email: string; private_key: string } | null | undefined;

function serviceAccount() {
  if (account !== undefined) return account;
  try {
    const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
    account = raw ? JSON.parse(raw) : null;
  } catch {
    account = null;
  }
  return account;
}

const b64url = (d: ArrayBuffer | string) =>
  btoa(typeof d === "string" ? d : String.fromCharCode(...new Uint8Array(d)))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function accessToken(): Promise<string | null> {
  const sa = serviceAccount();
  if (!sa) return null;
  if (token && token.until > Date.now() + 60_000) return token.value;
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const der = Uint8Array.from(
    atob(sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")),
    (c) => c.charCodeAt(0),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claim}`));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${head}.${claim}.${b64url(sig)}`,
  });
  const j = await r.json();
  if (!j.access_token) throw new Error("Firebase: не удалось получить токен: " + JSON.stringify(j).slice(0, 200));
  token = { value: j.access_token, until: Date.now() + (j.expires_in ?? 3600) * 1000 };
  return token.value;
}

/** Рассылает данные всем телефонам, подписанным на тему. false — Firebase не настроен. */
async function push(data: Record<string, string>): Promise<boolean> {
  const tk = await accessToken();
  if (!tk) return false;
  const r = await fetch(
    `https://fcm.googleapis.com/v1/projects/${serviceAccount()!.project_id}/messages:send`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${tk}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: { topic: "posts", data, android: { priority: "HIGH", ttl: "600s" } },
      }),
    },
  );
  if (!r.ok) throw new Error("Firebase: " + (await r.text()).slice(0, 200));
  return true;
}

// -------------------------------------------------------------------- цикл

function norm(s: string) {
  return s.toLowerCase().replace(/[’ʼ`]/g, "'");
}

async function checkChannel(ch: Channel, stats: Record<string, number>) {
  const posts = await fetchChannel(ch);
  if (posts.length === 0) return;
  const max = posts[posts.length - 1].id;
  if (max <= ch.last_id) return;
  // «Захватываем» новые номера: если параллельный запуск уже разослал — ничего не делаем.
  const claimed = await sql`
    update tryvoga.channels set last_id = ${max}, updated_at = now()
    where name = ${ch.name} and last_id < ${max} returning 1`;
  const first = ch.last_id === 0; // первое знакомство с каналом — только запоминаем номер
  const from = ch.last_id;
  ch.last_id = max;
  if (claimed.length === 0 || first) return;
  const re = ch.filter ? new RegExp(ch.filter, "i") : null;
  for (const p of posts) {
    if (p.id <= from || !p.text || p.text.length > ch.max_len) continue;
    if (re && !re.test(norm(p.text)) && !re.test(norm(p.reply))) continue;
    const sent = await push({
      t: "post",
      ch: ch.name,
      id: String(p.id),
      ts: String(p.ts),
      text: p.text.slice(0, 2500),
      reply: p.reply.slice(0, 300),
    });
    stats[sent ? "sent" : "unsent"] = (stats[sent ? "sent" : "unsent"] ?? 0) + 1;
  }
}

async function tick() {
  const stats: Record<string, number> = {};
  const channels = await sql<Channel[]>`select name, last_id, filter, max_len from tryvoga.channels`;
  for (let round = 0; round < ROUNDS; round++) {
    const started = Date.now();
    const results = await Promise.allSettled(channels.map((c) => checkChannel(c, stats)));
    const errors = results.filter((r) => r.status === "rejected")
      .map((r) => String((r as PromiseRejectedResult).reason?.message ?? r)).join("; ");
    await sql`
      insert into tryvoga.meta(key, value) values
        ('tick', ${new Date().toISOString()}), ('error', ${errors})
      on conflict (key) do update set value = excluded.value`;
    if (errors) console.error(errors);
    if (round < ROUNDS - 1) await new Promise((r) => setTimeout(r, Math.max(0, EVERY_MS - (Date.now() - started))));
  }
  // Раз в 10 минут — «я жив», чтобы телефон мог заметить, что сервер замолчал.
  const hb = await sql`select value from tryvoga.meta where key = 'heartbeat'`;
  if (!hb.length || Date.now() - Date.parse(hb[0].value) > HEARTBEAT_MS) {
    await sql`
      insert into tryvoga.meta(key, value) values ('heartbeat', ${new Date().toISOString()})
      on conflict (key) do update set value = excluded.value`;
    try {
      await push({ t: "hb", ts: String(Date.now()) });
    } catch (e) {
      console.error(String(e));
    }
  }
  console.log("tick done", JSON.stringify(stats));
}

Deno.serve(async (req: Request) => {
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });

  if (req.method === "GET") {
    const ch = await sql`select name, last_id from tryvoga.channels`;
    const meta = await sql`select key, value from tryvoga.meta where key in ('tick', 'error')`;
    const m = Object.fromEntries(meta.map((r) => [r.key, r.value]));
    return json({
      tick: m.tick ?? null,
      error: m.error ?? "",
      push: serviceAccount() != null,
      channels: Object.fromEntries(ch.map((r) => [r.name, Number(r.last_id)])),
    });
  }

  const secret = await sql`select value from tryvoga.meta where key = 'secret'`;
  if (!secret.length || req.headers.get("x-cron-secret") !== secret[0].value) {
    return json({ error: "forbidden" }, 403);
  }
  // Отвечаем сразу, работу продолжаем в фоне.
  // @ts-ignore: EdgeRuntime есть в среде Supabase
  EdgeRuntime.waitUntil(tick().catch((e) => console.error("tick failed", String(e))));
  return json({ started: true });
});
