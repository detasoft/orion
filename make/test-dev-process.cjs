const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, execFileSync } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const launcher = path.join(__dirname, 'dev-process.cjs');

async function fixture(t) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'orion-dev-process-'));
    const env = { ...process.env, ORION_DEV_RUN_DIR: directory };
    const processes = [];
    t.after(async () => {
        for (const child of processes) {
            if (child.exitCode === null) child.kill('SIGTERM');
        }
        await delay(200);
        fs.rmSync(directory, { recursive: true, force: true });
    });
    function command(...args) {
        return execFileSync(process.execPath, [launcher, ...args], { env, encoding: 'utf8' });
    }
    async function start(service = 'server', program = 'setInterval(() => {}, 1000)') {
        const child = spawn(process.execPath, [launcher, 'run', service, '--', process.execPath, '-e', program], {
            env, stdio: ['ignore', 'pipe', 'pipe'],
        });
        processes.push(child);
        let output = '';
        child.stderr.on('data', data => output += data);
        const file = path.join(directory, `${service}.pid`);
        const deadline = Date.now() + 5000;
        while (Date.now() < deadline) {
            if (child.exitCode !== null) throw new Error(output);
            if (fs.existsSync(file) && JSON.parse(fs.readFileSync(file)).process) return child;
            await delay(20);
        }
        throw new Error('Process did not start');
    }
    return { directory, command, start, env };
}

test('records each service, reports its process, rejects duplicates, and stops it', async t => {
    const f = await fixture(t);
    for (const service of ['server', 'agent', 'vite']) {
        await f.start(service);
        assert.match(f.command('status', service), /running \(PID \d+\)/);
        assert.throws(() => f.command('run', service, '--', process.execPath, '-e', 'process.exit(23)'),
            /already has a process record/);
        assert.match(f.command('stop', service), /stopped/);
        assert.equal(fs.existsSync(path.join(f.directory, `${service}.pid`)), false);
    }
});

test('a stale PID identity never signals an unrelated live process', async t => {
    const f = await fixture(t);
    const file = path.join(f.directory, 'server.pid');
    const wrongIdentity = { pid: process.pid, started: 'a different process start time' };
    fs.writeFileSync(file, JSON.stringify({ owner: wrongIdentity, process: wrongIdentity }));
    assert.match(f.command('status', 'server'), /stale record/);
    f.command('stop', 'server');
    assert.equal(fs.existsSync(file), false);
});

test('stop recovers a running child after its supervisor is killed', async t => {
    const f = await fixture(t);
    const supervisor = await f.start();
    supervisor.kill('SIGKILL');
    await new Promise(resolve => supervisor.once('exit', resolve));
    assert.match(f.command('status', 'server'), /running/);
    f.command('stop', 'server');
    assert.match(f.command('status', 'server'), /stopped/);
});

test('a command failure removes its record and preserves the exit status', async t => {
    const f = await fixture(t);
    assert.throws(() => f.command('run', 'agent', '--', process.execPath, '-e', 'process.exit(23)'),
        error => error.status === 23);
    assert.equal(fs.existsSync(path.join(f.directory, 'agent.pid')), false);
});

test('orphan recovery also stops a descendant that ignores SIGTERM', async t => {
    const f = await fixture(t);
    const portFile = path.join(f.directory, 'port');
    const worker = `process.on('SIGTERM', () => {});
        const server = require('node:net').createServer();
        server.listen(0, '127.0.0.1', () => require('node:fs').writeFileSync(
            ${JSON.stringify(portFile)}, String(server.address().port)));`;
    const program = `require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(worker)}]);
        setInterval(() => {}, 1000);`;
    const supervisor = await f.start('agent', program);
    const deadline = Date.now() + 5000;
    while (!fs.existsSync(portFile) && Date.now() < deadline) await delay(20);
    const port = Number(fs.readFileSync(portFile, 'utf8'));
    supervisor.kill('SIGKILL');
    await new Promise(resolve => supervisor.once('exit', resolve));
    f.command('stop', 'agent');
    await new Promise((resolve, reject) => {
        const socket = require('node:net').connect(port, '127.0.0.1');
        socket.on('error', resolve);
        socket.on('connect', () => {
            socket.destroy();
            reject(new Error('Orphaned descendant is still listening'));
        });
    });
});
