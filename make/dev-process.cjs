// Process records are local to this checkout; they do not serialize builds.
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { constants } = require('node:os');

const services = ['server', 'agent', 'vite'];
const directory = path.resolve(__dirname, '..', process.env.ORION_DEV_RUN_DIR || 'target/dev-processes');

function identity(pid) {
    if (!Number.isSafeInteger(pid) || pid <= 0) return null;
    const result = spawnSync('ps', ['-p', String(pid), '-o', 'lstart=', '-o', 'stat='], {
        encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' },
    });
    if (result.error) throw result.error;
    if (result.status !== 0) return null;
    const fields = result.stdout.trim().split(/\s+/);
    if (fields.at(-1)?.startsWith('Z')) return null;
    return { pid, started: fields.slice(0, -1).join(' ') };
}

function alive(record) {
    return record && identity(record.pid)?.started === record.started;
}

function filename(service) {
    if (!services.includes(service)) throw new Error(`Unknown service: ${service}`);
    return path.join(directory, `${service}.pid`);
}

function read(file) {
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
        if (error.code === 'ENOENT') return null;
        throw new Error(`Cannot read ${file}: ${error.message}`);
    }
}

function removeOwned(file, owner) {
    const current = read(file);
    if (current?.owner?.pid === owner.pid && current.owner.started === owner.started) fs.unlinkSync(file);
}

function groupSignal(pid, signal) {
    try {
        process.kill(-pid, signal);
        return true;
    } catch (error) {
        if (error.code !== 'ESRCH') throw error;
        return false;
    }
}

async function run(service, command, args) {
    const file = filename(service);
    fs.mkdirSync(directory, { recursive: true });
    const owner = identity(process.pid);
    const record = { owner, process: null };
    // Publish a complete initial record exclusively, before starting any child.
    const temporary = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(record), { flag: 'wx', mode: 0o600 });
    try {
        fs.linkSync(temporary, file);
    } catch (error) {
        if (error.code !== 'EEXIST') throw error;
        throw new Error(`${service} already has a process record; use make status or make stop SERVICE=${service}.`);
    } finally {
        fs.unlinkSync(temporary);
    }

    let child;
    let stopping = false;
    let timer;
    function stop(code, signal = 'SIGTERM') {
        if (stopping) return;
        stopping = true;
        process.exitCode = code;
        if (!child?.pid) {
            removeOwned(file, owner);
            return;
        }
        groupSignal(child.pid, signal);
        const deadline = Date.now() + 10000;
        timer = setInterval(() => {
            const running = groupSignal(child.pid, 0);
            if (running && Date.now() < deadline) return;
            if (running) groupSignal(child.pid, 'SIGKILL');
            clearInterval(timer);
            removeOwned(file, owner);
        }, 50);
    }
    for (const signal of ['SIGINT', 'SIGTERM']) {
        process.on(signal, () => stop(128 + constants.signals[signal], signal));
    }
    try {
        child = spawn(command, args, { detached: true, stdio: 'inherit' });
        child.on('error', error => {
            console.error(error.message);
            stop(1);
        });
        child.on('exit', (code, signal) => stop(code ?? 128 + constants.signals[signal]));
        record.process = child.pid ? identity(child.pid) : null;
        fs.writeFileSync(temporary, JSON.stringify(record), { flag: 'wx', mode: 0o600 });
        fs.renameSync(temporary, file);
    } catch (error) {
        stop(1);
        throw error;
    }
}

async function stopService(service) {
    const file = filename(service);
    const record = read(file);
    if (!record) {
        console.log(`${service}: stopped`);
        return;
    }
    if (alive(record.owner)) {
        process.kill(record.owner.pid, 'SIGTERM');
        const deadline = Date.now() + 12000;
        while (alive(record.owner) && Date.now() < deadline) await delay(100);
        if (alive(record.owner)) throw new Error(`${service}: supervisor did not stop; record retained.`);
    }
    // Recover a child whose supervisor was killed without running its cleanup.
    if (alive(record.process)) {
        groupSignal(record.process.pid, 'SIGTERM');
        const deadline = Date.now() + 10000;
        while (groupSignal(record.process.pid, 0) && Date.now() < deadline) await delay(100);
        if (groupSignal(record.process.pid, 0)) {
            groupSignal(record.process.pid, 'SIGKILL');
            await delay(100);
        }
    }
    removeOwned(file, record.owner);
    console.log(`${service}: stopped`);
}

async function main() {
    const [action, service, separator, command, ...args] = process.argv.slice(2);
    if (action === 'run') {
        if (separator !== '--' || !command) throw new Error('Usage: dev-process.cjs run <service> -- <command> [args]');
        await run(service, command, args);
        return;
    }
    if (!['status', 'stop'].includes(action)) throw new Error('Expected run, status or stop.');
    const selected = service ? [service] : services;
    for (const name of selected) {
        if (action === 'stop') {
            await stopService(name);
        } else {
            const record = read(filename(name));
            const child = alive(record?.process);
            const owner = alive(record?.owner);
            console.log(`${name}: ${child ? `running (PID ${record.process.pid})`
                : owner ? 'starting/stopping' : record ? 'stale record (not running)' : 'stopped'}`);
        }
    }
}

main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
});
