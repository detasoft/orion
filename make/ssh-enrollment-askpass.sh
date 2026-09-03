#!/bin/sh

case "$1" in
    "Orion password:"*|\(*\)\ "Orion password:"*)
        printf '%s\n' "$ORION_ROOT_PASSWORD"
        ;;
    "Keys "*|\(*\)\ "Keys "*)
        printf 'all\n'
        ;;
    *)
        exit 1
        ;;
esac
