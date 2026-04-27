#!/bin/bash
echo "启动推演平台..."

# Kill any existing processes on these ports
lsof -ti:8000 | xargs kill -9 2>/dev/null
lsof -ti:5173 | xargs kill -9 2>/dev/null

# Start backend (Spring Boot)
cd "$(dirname "$0")/backend"
mvn spring-boot:run &
BACKEND_PID=$!
echo "后端启动 (PID: $BACKEND_PID) → http://localhost:8000"

# Start frontend
cd "$(dirname "$0")/frontend"
npm run dev &
FRONTEND_PID=$!
echo "前端启动 (PID: $FRONTEND_PID) → http://localhost:5173"

echo ""
echo "按 Ctrl+C 停止所有服务"
wait
