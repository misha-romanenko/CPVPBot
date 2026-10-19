#!/usr/bin/env node
// CPVPBOT2.0 dummy bot - headless mineflayer bot named "dummy"
// Its only job: join the server, accept duels from cpvpbot, move around as a target.

const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')

const HOST = process.env.BOT_HOST || 'valkyriemc.net'
const PORT = parseInt(process.env.BOT_PORT || '25565', 10)
const VERSION = process.env.BOT_VERSION || '1.18.2'
const NAME = 'dummy'
const PASSWORD = process.env.BOT_PASSWORD || ''
const OPPONENT = 'cpvpbot'
const LOG_DIR = path.join(__dirname, '..', 'logs')
fs.mkdirSync(LOG_DIR, { recursive: true })
const LOG_FILE = path.join(LOG_DIR, 'dummy.log')

let duelActive = false
let acceptStage = 0
let registered = false
let loggedIn = false
let wanderTimer = null

function log (line) {
  const stamped = `[${new Date().toISOString()}] ${line}`
  console.log(stamped)
  try { fs.appendFileSync(LOG_FILE, stamped + '\n') } catch (e) {}
}

function fmtPos (p) {
  if (!p) return '?'
  return `(${p.x.toFixed(1)}, ${p.y.toFixed(1)}, ${p.z.toFixed(1)})`
}

function createBot () {
  log(`connecting to ${HOST}:${PORT} as ${NAME} (version ${VERSION})`)
  const bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: NAME,
    auth: 'offline',
    version: VERSION,
    hideErrors: true,
    checkTimeoutInterval: 60000
  })
  bot.loadPlugin(pathfinder)

  bot.on('login', () => log('logged in (login success)'))
  bot.on('spawn', () => {
    log(`spawned at ${fmtPos(bot.entity && bot.entity.position)}`)
    duelActive = false
    acceptStage = 0
    try {
      const movements = new Movements(bot)
      movements.can111 = true
      bot.pathfinder.setMovements(movements)
    } catch (e) { log('movements setup failed: ' + e.message) }
    startWander(bot)
  })

  bot.on('messagestr', (msg, pos) => {
    if (!msg || msg.length === 0) return
    log(`CHAT[${pos}] ${msg}`)
    handleChat(bot, msg)
  })

  bot.on('health', () => {
    log(`health=${bot.health.toFixed(1)} food=${bot.food.toFixed(1)}`)
  })

  bot.on('death', () => {
    log('DIED - will respawn')
    duelActive = false
    acceptStage = 0
  })

  bot.on('kicked', (reason) => {
    log('KICKED: ' + JSON.stringify(reason))
    registered = false
    loggedIn = false
    scheduleReconnect()
  })
  bot.on('error', (err) => log('ERROR: ' + (err && err.message)))
  bot.on('end', (reason) => {
    log('disconnected: ' + (reason || 'unknown'))
    if (wanderTimer) { clearInterval(wanderTimer); wanderTimer = null }
    scheduleReconnect()
  })

  return bot
}

let botRef = null
let reconnectTimer = null
function scheduleReconnect () {
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    log('reconnecting...')
    botRef = createBot()
  }, 5000)
}

// --- auth + duel acceptance ------------------------------------------
function handleChat (bot, msg) {
  const lower = msg.toLowerCase()

  // AuthMe prompts
  if (!registered && (lower.includes('register') && (lower.includes('/register') || lower.includes('usage')))) {
    log('auth prompt -> sending /register')
    safeChat(bot, `/register ${PASSWORD} ${PASSWORD}`, 800)
    registered = true
    return
  }
  if (!loggedIn && lower.includes('/login')) {
    log('auth prompt -> sending /login')
    safeChat(bot, `/login ${PASSWORD}`, 800)
    loggedIn = true
    return
  }

  // duel request patterns from common practice plugins
  const reqMatch = msg.match(/duel request from (\S+) in/i)
  if (reqMatch && !duelActive) {
    const from = reqMatch[1]
    log(`duel request from ${from} -> sending /duel accept ${from}`)
    safeChat(bot, `/duel accept ${from}`, 700)
    return
  }
  const looksLikeRequest =
    (lower.includes('duel') || lower.includes('challenge')) &&
    (lower.includes('accept') || lower.includes('request') || lower.includes('wants') ||
     lower.includes('challenged') || lower.includes('invited') || lower.includes('click'))

  if (looksLikeRequest && !duelActive) {
    if (acceptStage === 0) {
      acceptStage = 1
      log('duel request detected -> sending /duel accept cpvpbot')
      safeChat(bot, `/duel accept ${OPPONENT}`, 900)
    } else if (acceptStage === 1) {
      acceptStage = 2
      log('retrying /duel accept cpvpbot')
      safeChat(bot, `/duel accept ${OPPONENT}`, 1200)
    }
    return
  }

  if (lower.includes('duel started') || lower.includes('the duel') || lower.includes('fight') ||
      (lower.includes('started') && lower.includes(OPPONENT))) {
    duelActive = true
    log('DUEL ACTIVE')
    return
  }
  if (lower.includes('won') || lower.includes('victory') || lower.includes('loser') ||
      lower.includes('duel ended') || lower.includes('draw')) {
    duelActive = false
    acceptStage = 0
    log('DUEL OVER')
  }
}

function safeChat (bot, text, delay) {
  setTimeout(() => {
    try { bot.chat(text); log('SENT: ' + text) } catch (e) { log('chat failed: ' + e.message) }
  }, delay || 300)
}

// --- movement --------------------------------------------------------
function lookAtOpponent (bot) {
  const ent = bot.entities[bot.players[OPPONENT] && bot.players[OPPONENT].entity ? bot.players[OPPONENT].entity.id : -1]
  if (ent) bot.lookAt(ent.position.offset(0, ent.height * 0.85, 0)).catch(() => {})
}

function startWander (bot) {
  if (wanderTimer) clearInterval(wanderTimer)
  let strafeFlip = 0
  wanderTimer = setInterval(() => {
    if (!bot.entity) return
    try {
      if (duelActive) {
        // strafe-fight movement so observers can see the dummy move during the duel
        strafeFlip++
        if (strafeFlip % 4 === 0) {
          bot.setControlState('forward', Math.random() > 0.35)
          bot.setControlState('jump', Math.random() > 0.7)
        }
        if (strafeFlip % 6 === 0) {
          const dir = Math.random() > 0.5 ? 'left' : 'right'
          bot.setControlState(dir, true)
          setTimeout(() => bot.setControlState(dir, false), 400 + Math.random() * 500)
        }
        if (Math.random() > 0.75) lookAtOpponent(bot)
      } else {
        // idle wander: pathfinder to a nearby spot every ~4s
        bot.clearControlStates()
        strafeFlip++
        if (strafeFlip % 6 === 0) {
          const p = bot.entity.position
          const gx = p.x + (Math.random() * 16 - 8)
          const gz = p.z + (Math.random() * 16 - 8)
          bot.pathfinder.goto(new goals.GoalNear(gx, p.y, gz, 1.5), true)
            .then(() => log('wandered to ' + fmtPos({ x: gx, y: p.y, z: gz })))
            .catch(() => {})
        }
      }
    } catch (e) { /* ignore transient movement errors */ }
  }, 700)
}

botRef = createBot()
log('dummy bot controller started')
