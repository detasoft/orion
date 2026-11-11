// Node is already required by Vite and the development browser opener.
const { spawn } = require('node:child_process');
const { resolve } = require('node:path');
const { constants } = require('node:os');

// Recursive Make recipes also execute under make -n.
if (/^[a-zA-Z]*n/.test((process.env.MAKEFLAGS || '').split(' ')[0])) process.exit(0);

const children = [];
let stopping = false;

function signalGroup(child, signal) {
    if (!child.pid) return false;
    try {
        process.kill(-child.pid, signal);
        return true;
    } catch (error) {
        if (signal === 0 && error.code === 'EPERM') return true;
        if (error.code !== 'ESRCH') throw error;
        return false;
    }
}

function stop(code, signal = 'SIGTERM') {
    if (stopping) return;
    stopping = true;
    process.exitCode = code;
    for (const child of children) signalGroup(child, signal);
    const deadline = Date.now() + 10000;
    const timer = setInterval(() => {
        const alive = children.filter(child => signalGroup(child, 0));
        if (Date.now() >= deadline) {
            for (const child of alive) signalGroup(child, 'SIGKILL');
        }
        if (!alive.length || Date.now() >= deadline) clearInterval(timer);
    }, 50);
}

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => stop(128 + constants.signals[signal], signal));
}

function start(command, args, options = {}) {
    const child = spawn(command, args, {
        detached: true, stdio: ['ignore', 'inherit', 'inherit'], ...options,
    });
    children.push(child);
    child.on('error', error => {
        console.error(`Cannot start development service: ${error.message}`);
        stop(1);
    });
    child.on('exit', (code, signal) => stop(code ?? 128 + constants.signals[signal]));
}

console.log('Starting Orion and Vite; press Ctrl-C to stop both.');
console.log(`Orion backend: ${process.env.VITE_BACKEND_URL}`);
console.log('Vite UI: use the Local URL printed by npm below.');
start(process.execPath, [resolve('make/dev-process.cjs'), 'run', 'server', '--', ...process.argv.slice(3)]);
start('/bin/sh', ['-c', `${process.argv[2] || 'npm'} run dev -- --open`], {
    cwd: resolve('net/frontend/ui'),
    env: { ...process.env, BROWSER: process.env.BROWSER || resolve('make/open-dev-ui.js') },
});
