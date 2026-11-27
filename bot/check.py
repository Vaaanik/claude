#!/usr/bin/env python3
"""Следит за графиком отключений ДТЭК (Днепр и область) для одной группы
и пишет в Telegram, когда график меняется или скоро отключение.

Запускается по расписанию из GitHub Actions. Только стандартная библиотека.

Настройки (переменные окружения):
  GROUP               группа/очередь, например 8.1
  TELEGRAM_BOT_TOKEN  токен бота от @BotFather
  TELEGRAM_CHAT_ID    (необязательно) id чата; если не задан, берётся
                      из последнего сообщения, которое вы написали боту
  REMIND_MINUTES      за сколько минут предупреждать (по умолчанию 30)
  ALERTS              1 — ещё и писать о начале/отбое воздушной тревоги
  ALERT_REGION        область для тревог
  STATE_FILE          где хранить состояние между запусками
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

try:
    from zoneinfo import ZoneInfo

    try:
        KYIV = ZoneInfo("Europe/Kyiv")
    except Exception:
        KYIV = ZoneInfo("Europe/Kiev")
except Exception:  # совсем старый Python
    KYIV = timezone(timedelta(hours=2))

SCHEDULE_URL = os.environ.get(
    "SCHEDULE_URL",
    "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json",
)
ALERTS_URL = os.environ.get("ALERTS_URL", "https://ubilling.net.ua/aerialalerts/")

GROUP = os.environ.get("GROUP", "8.1").strip()
REMIND_MINUTES = int(os.environ.get("REMIND_MINUTES") or 30)
# Запуски по расписанию приходят неровно, поэтому окно напоминания шире.
REMIND_SLACK = 7
ALERTS = os.environ.get("ALERTS", "0").strip() == "1"
ALERT_REGION = os.environ.get("ALERT_REGION") or "Дніпропетровська область"
STATE_FILE = os.environ.get("STATE_FILE", "state/state.json")
TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "").strip()

# Состояние одного получаса: X — света нет, ? — возможно отключение, . — свет есть
HOUR_TO_HALVES = {
    "yes": "..",
    "no": "XX",
    "maybe": "??",
    "first": "X.",
    "second": ".X",
    "mfirst": "?.",
    "msecond": ".?",
}


def http_json(url, data=None, timeout=25):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "outage-notifier"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def day_slots(day):
    """Расписание одного дня -> строка из 48 символов (по получасам)."""
    out = []
    for h in range(1, 25):
        out.append(HOUR_TO_HALVES.get(day.get(str(h), "yes"), ".."))
    return "".join(out)


def intervals(slots, mark):
    """Строка получасов -> список (начало, конец) в номерах получасов."""
    res, start = [], None
    for i, c in enumerate(slots + " "):
        if c == mark and start is None:
            start = i
        elif c != mark and start is not None:
            res.append((start, i))
            start = None
    return res


def hhmm(half):
    return "%02d:%02d" % (half // 2, 30 * (half % 2))


def fmt_day(slots):
    off = intervals(slots, "X")
    maybe = intervals(slots, "?")
    lines = []
    if off:
        total = sum(b - a for a, b in off) / 2
        lines.append("🔴 Света нет: " + ", ".join(f"{hhmm(a)}–{hhmm(b)}" for a, b in off))
        lines.append(f"Всего без света: {total:g} ч")
    if maybe:
        lines.append("🟡 Возможно отключение: " + ", ".join(f"{hhmm(a)}–{hhmm(b)}" for a, b in maybe))
    if not lines:
        lines.append("🟢 Отключений по графику нет")
    return "\n".join(lines)


def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(state):
    os.makedirs(os.path.dirname(STATE_FILE) or ".", exist_ok=True)
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=1)


def tg(method, **params):
    data = urllib.parse.urlencode(params).encode()
    return http_json(f"https://api.telegram.org/bot{TOKEN}/{method}", data=data)


def resolve_chat(state):
    """Возвращает (chat_id, только_что_найден)."""
    if CHAT_ID:
        return CHAT_ID, False
    if state.get("chat_id"):
        return state["chat_id"], False
    if not TOKEN:
        return None, False
    updates = tg("getUpdates").get("result", [])
    for u in reversed(updates):
        chat = (u.get("message") or {}).get("chat") or {}
        if chat.get("type") == "private":
            state["chat_id"] = str(chat["id"])
            return state["chat_id"], True
    return None, False


def main():
    now = datetime.now(KYIV)
    state = load_state()
    messages = []

    data = http_json(SCHEDULE_URL)
    fact = (data.get("fact") or {}).get("data") or {}
    key = "GPV" + GROUP
    updated = (data.get("fact") or {}).get("update", "")

    # --- график по дням -------------------------------------------------
    days = {}  # 'YYYY-MM-DD' -> строка получасов
    missing = False
    for ts, groups in fact.items():
        date = datetime.fromtimestamp(int(ts), KYIV).date()
        if date < now.date():
            continue
        if key in groups:
            days[date.isoformat()] = day_slots(groups[key])
        else:
            missing = True
            available = sorted(
                (g[3:] for g in groups), key=lambda s: [int(x) for x in s.split(".")]
            )

    if missing and not days:
        if not state.get("missing_notified"):
            messages.append(
                f"⚠️ В графике ДТЭК сейчас нет группы {GROUP}.\n"
                f"Есть группы: {', '.join(available)}.\n"
                "Проверьте номер своей очереди на dtek-dnem.com.ua (поиск по адресу) "
                "и поправьте GROUP в настройках репозитория."
            )
            state["missing_notified"] = True
    else:
        state.pop("missing_notified", None)

    known = state.get("days", {})
    first_run = "days" not in state
    for date, slots in sorted(days.items()):
        if known.get(date) == slots:
            continue
        d = datetime.fromisoformat(date).date()
        label = "сегодня" if d == now.date() else "завтра" if d == now.date() + timedelta(days=1) else date
        if date in known:
            head = f"🔄 График изменился — {label}, {d:%d.%m}"
        elif first_run:
            head = f"💡 График на {label}, {d:%d.%m}"
        else:
            head = f"📅 Появился график на {label}, {d:%d.%m}"
        tail = f"\n\nГруппа {GROUP}" + (f" · обновлено {updated}" if updated else "")
        messages.append(head + "\n" + fmt_day(slots) + tail)
    state["days"] = days

    # --- напоминания ----------------------------------------------------
    # Склеиваем дни в одну ленту, чтобы отключение через полночь было одним.
    spans = []
    for date, slots in sorted(days.items()):
        base = datetime.fromisoformat(date).replace(tzinfo=KYIV)
        for a, b in intervals(slots, "X"):
            s, e = base + timedelta(minutes=30 * a), base + timedelta(minutes=30 * b)
            if spans and spans[-1][1] == s:
                spans[-1] = (spans[-1][0], e)
            else:
                spans.append((s, e))

    sent = set(state.get("reminded", []))
    window = timedelta(minutes=REMIND_MINUTES + REMIND_SLACK)
    for s, e in spans:
        for kind, moment in (("off", s), ("on", e)):
            rid = f"{kind}|{moment.isoformat()}"
            left = moment - now
            if timedelta(0) < left <= window and rid not in sent:
                mins = max(1, round(left.total_seconds() / 60))
                if kind == "off":
                    messages.append(
                        f"⏰ Через {mins} мин отключат свет\n"
                        f"{s:%H:%M}–{e:%H:%M} · группа {GROUP}"
                    )
                else:
                    messages.append(
                        f"💡 Через {mins} мин должны включить свет\n"
                        f"в {e:%H:%M} · группа {GROUP}"
                    )
                sent.add(rid)
    # не копим старые отметки
    cutoff = (now - timedelta(days=2)).isoformat()
    state["reminded"] = sorted(r for r in sent if r.split("|", 1)[1] > cutoff)

    # --- объявление ДТЭК (экстренные отключения и т.п.) -----------------
    att_hash = (data.get("meta") or {}).get("attentionHash")
    att_text = ((data.get("attention") or {}).get("text") or "").strip()
    if att_hash != state.get("attention"):
        if att_text:
            messages.append("📢 Сообщение ДТЭК:\n" + att_text)
        state["attention"] = att_hash

    # --- тревога (по желанию) -------------------------------------------
    if ALERTS:
        try:
            st = http_json(ALERTS_URL)["states"][ALERT_REGION]
            if "alert" in state and state["alert"] != st["alertnow"]:
                messages.append(
                    f"🚨 Воздушная тревога — {ALERT_REGION}"
                    if st["alertnow"]
                    else f"✅ Отбой тревоги — {ALERT_REGION}"
                )
            state["alert"] = st["alertnow"]
        except Exception as ex:
            print("alerts: не удалось получить статус:", ex)

    # --- отправка -------------------------------------------------------
    chat, fresh = resolve_chat(state)
    if fresh:
        messages.insert(
            0,
            f"✅ Бот подключён. Слежу за группой {GROUP}.\n"
            f"Ваш chat id: {chat} — добавьте его в секрет TELEGRAM_CHAT_ID, "
            "чтобы бот не потерял вас.",
        )

    if not messages:
        print("Изменений нет.")
    for m in messages:
        print("-----\n" + m)

    if messages and TOKEN and chat:
        for m in messages:
            tg("sendMessage", chat_id=chat, text=m[:4000], disable_web_page_preview="true")
        save_state(state)
    elif messages and TOKEN and not chat:
        # Не сохраняем состояние: пришлём всё, когда появится получатель.
        print("\nНе знаю, кому писать: откройте бота в Telegram и нажмите Start.")
        sys.exit(1)
    else:
        if messages:
            print("\n(TELEGRAM_BOT_TOKEN не задан — сообщения только напечатаны.)")
        save_state(state)


if __name__ == "__main__":
    main()
