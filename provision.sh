#!/usr/bin/env bash

POSTGRES_PASSWORD=$(uuidgen)

add-apt-repository ppa:git-core/ppa
add-apt-repository ppa:openjdk-r/ppa

curl -sL https://deb.nodesource.com/setup_6.x | sudo -E bash -
sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt/ $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
wget --quiet -O- https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -
f

apt-get update
apt-get install -y build-essential openjdk-8-jdk postgresql-9.6 nodejs

wget -qO- https://toolbelt.heroku.com/install-ubuntu.sh | sh

sudo -u postgres psql -c "ALTER USER postgres PASSWORD '$POSTGRES_PASSWORD';"
sed -i "s/#listen_addresses = 'localhost'/listen_addresses = '*'/" /etc/postgresql/9.6/main/postgresql.conf
echo "host    all             all             all                     md5" >> /etc/postgresql/9.6/main/pg_hba.conf
service postgresql restart

cat >> /etc/environment <<EOF
DATABASE_URL=postgresql://postgres:${POSTGRES_PASSWORD}@localhost:5432/zot_world
EOF

cd /usr/local/bin
wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
chmod a+x lein

runuser -l vagrant -c "lein"
