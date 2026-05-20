module.exports = {
  apps: [{
    name: 'chat-server',
    script: 'index.js',
    instances: 'max',
    exec_mode: 'cluster',
    env: {
      NODE_ENV: 'production',
      PORT: 3500
    },
    max_memory_restart: '512M',
    watch: false,
    autorestart: true,
    max_restarts: 10,
    restart_delay: 4000
  }]
};
