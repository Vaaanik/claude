#!/usr/bin/env bash
# Установка «Второго мозга» на чистый сервер Ubuntu 22.04/24.04.
# Запуск под root:  curl -fsSL https://raw.githubusercontent.com/vaaanik/claude/second-brain-bot/second_brain/deploy/setup.sh | bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/vaaanik/claude.git}"
BRANCH="${BRANCH:-second-brain-bot}"
APP_USER=brain
APP_HOME=/home/$APP_USER
APP_DIR=$APP_HOME/claude/second_brain

apt-get update -q
apt-get install -y -q git python3 python3-venv python3-pip

# Claude Code не работает в режиме полного доступа под root — заводим отдельного пользователя
id -u $APP_USER >/dev/null 2>&1 || useradd -m -s /bin/bash $APP_USER

if [ ! -d "$APP_HOME/claude" ]; then
  sudo -u $APP_USER git clone -b "$BRANCH" "$REPO_URL" "$APP_HOME/claude"
else
  sudo -u $APP_USER git -C "$APP_HOME/claude" pull
fi

sudo -u $APP_USER python3 -m venv "$APP_DIR/.venv"
sudo -u $APP_USER "$APP_DIR/.venv/bin/pip" install -q -r "$APP_DIR/requirements.txt"
[ -f "$APP_DIR/.env" ] || sudo -u $APP_USER cp "$APP_DIR/.env.example" "$APP_DIR/.env"

cp "$APP_DIR/deploy/second-brain.service" /etc/systemd/system/second-brain.service
systemctl daemon-reload
systemctl enable second-brain >/dev/null

cat <<EOF

✅ Установлено. Осталось:
  1. nano $APP_DIR/.env      — впишите TELEGRAM_BOT_TOKEN, ALLOWED_USER_IDS и ANTHROPIC_API_KEY
  2. systemctl restart second-brain
  3. journalctl -u second-brain -f   — смотреть логи
EOF
