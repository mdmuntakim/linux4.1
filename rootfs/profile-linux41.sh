#!/bin/sh
# /etc/profile.d/linux41.sh
export PS1='linux41:\w# '
export EDITOR=vi
alias ll='ls -la'
echo "Linux4.1 guest - kernel $(uname -r), $(nproc) vCPU, $(free -m | awk '/Mem:/{print $2}') MiB RAM"
