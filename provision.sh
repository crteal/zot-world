#!/usr/bin/env bash

RABBITMQ_USER=$(uuidgen)
RABBITMQ_PASSWORD=$(uuidgen)
POSTGRES_PASSWORD=$(uuidgen)

add-apt-repository ppa:git-core/ppa
add-apt-repository ppa:openjdk-r/ppa

echo 'deb http://www.rabbitmq.com/debian/ testing main' | tee /etc/apt/sources.list.d/rabbitmq.list
wget -O- https://www.rabbitmq.com/rabbitmq-release-signing-key.asc | sudo apt-key add -

curl -sL https://deb.nodesource.com/setup_6.x | sudo -E bash -
sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt/ $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
wget --quiet -O- https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -
f

apt-get update
apt-get install -y build-essential nodejs openjdk-8-jdk postgresql-9.6 rabbitmq-server

wget -qO- https://toolbelt.heroku.com/install-ubuntu.sh | sh

rabbitmqctl add_user '$RABBITMQ_USER' '$RABBITMQ_PASSWORD'
rabbitmqctl set_user_tags '$RABBITMQ_USER' administrator
rabbitmqctl set_permissions -p / '$RABBITMQ_USER' ".*" ".*" ".*"

sudo -u postgres psql -c "ALTER USER postgres PASSWORD '$POSTGRES_PASSWORD';"
sed -i "s/#listen_addresses = 'localhost'/listen_addresses = '*'/" /etc/postgresql/9.6/main/postgresql.conf
echo "host    all             all             all                     md5" >> /etc/postgresql/9.6/main/pg_hba.conf
service postgresql restart

cat >> /etc/environment <<EOF
DATABASE_URL=postgresql://postgres:${POSTGRES_PASSWORD}@localhost:5432/zot_world
RABBITMQ_BIGWIG_RX_URL=amqp://${RABBITMQ_USER}:${RABBITMQ_PASSWORD}@localhost
RABBITMQ_BIGWIG_TX_URL=amqp://${RABBITMQ_USER}:${RABBITMQ_PASSWORD}@localhost
EOF

cd /usr/local/bin
wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
chmod a+x lein

runuser -l vagrant -c "lein"
