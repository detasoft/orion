#!/bin/sh

case "$1" in
    "Enrollment token:"*)
        printf '%s\n' "$ORION_SSH_ENROLLMENT_TOKEN"
        ;;
    "Keys "*)
        printf 'all\n'
        ;;
    *)
        exit 1
        ;;
esac
