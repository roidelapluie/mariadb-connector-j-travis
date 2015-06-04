#!/bin/bash

set -x

sudo service mysql stop
sudo apt-get remove --purge mysql-server mysql-client mysql-common
sudo apt-get autoremove
sudo apt-get autoclean
sudo rm -rf /etc/mysql||true

sudo apt-get install python-software-properties

sudo apt-key adv --recv-keys --keyserver keyserver.ubuntu.com 0xcbcb082a1bb943db
sudo add-apt-repository "deb http://ftp.igh.cnrs.fr/pub/mariadb/repo/${MARIA_VERSION}/debian wheezy main"

sudo apt-get update

sudo apt-get install mariadb-server
sudo tee /etc/mysql/conf.d/mps.cnf << END
[mysqld]
max_allowed_packet=$MAX_ALLOWED_PACKET
END

sudo service mysql restart

mysql -u root -e "create database test"
