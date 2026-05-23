import { createApp } from 'vue'
import App from './App.vue'
import PreviewView from './components/views/PreviewView.vue'
import './index.css'

// 通过 query string 切换"只读预览模式":一个新 tab 里只渲染指定本体模型的图谱
const params = new URLSearchParams(window.location.search);
const root = params.get('preview') ? PreviewView : App;
createApp(root).mount('#root')
