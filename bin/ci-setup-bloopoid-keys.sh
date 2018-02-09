#!/usr/bin/env bash

# Add git metadata for the push to succeed
git config --global user.name "Bloopoid"
git config --global user.email "bloopoid@trashmail.ws"

# Setup everything to be able to push to github pages
mkdir -p ~/.ssh
echo -e "Host github.com\\n\\tStrictHostKeyChecking no\\n" > ~/.ssh/config
echo -e "${BLOOPOID_PRIVATE_KEY}\\n" > ~/.ssh/id_rsa
chmod 0600 ~/.ssh/id_rsa
