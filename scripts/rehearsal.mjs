#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { existsSync } from 'node:fs';
import net from 'node:net';
const api=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const web=resolve(api,'../meridian-web'),mobile=resolve(api,'../meridian-mobile/preview');
const apiPort=Number(process.env.API_PORT||8080),webPort=Number(process.env.WEB_PORT||5175),mobilePort=Number(process.env.MOBILE_PORT||5176);
const children=[];
let stopping=false;
function stop(code=0){if(stopping)return;stopping=true;for(const child of children)child.kill('SIGTERM');setTimeout(()=>process.exit(code),500).unref();}
process.on('SIGINT',()=>stop());process.on('SIGTERM',()=>stop());
async function run(command,args,cwd,env={}){return new Promise((res,rej)=>{const c=spawn(command,args,{cwd,env:{...process.env,...env},stdio:'inherit'});c.on('error',rej);c.on('exit',code=>code===0?res():rej(new Error(`${command} failed (${code})`)));});}
function server(command,args,cwd,env={}){const c=spawn(command,args,{cwd,env:{...process.env,...env},stdio:'inherit'});children.push(c);c.on('error',e=>{console.error(e.message);stop(1)});c.on('exit',()=>{if(!stopping){console.error(`${command} exited`);stop(1)}});return c;}
function unused(port){return new Promise((res,rej)=>{const s=net.createServer();s.once('error',()=>rej(new Error(`Port ${port} is in use. Set API_PORT, WEB_PORT, MOBILE_PORT without stopping unrelated processes.`)));s.listen(port,'127.0.0.1',()=>s.close(res));});}
try {
  for(const folder of [api,web,mobile])if(!existsSync(folder))throw new Error(`Missing sibling checkout: ${folder}`);
  await Promise.all([apiPort,webPort,mobilePort].map(unused));
  await run('mvn',['-q','verify'],api);
  for(const folder of [web,mobile])if(!existsSync(resolve(folder,'node_modules')))await run('npm',['ci'],folder);
  server('java',['-jar','target/meridian-api-1.0.0.jar'],api,{PORT:String(apiPort)});
  let ready=false;
  for(let i=0;i<60;i++){try{const r=await fetch(`http://127.0.0.1:${apiPort}/api/v1/health`);if(r.ok&&(await r.json()).service==='meridian-api'){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,500));}
  if(!ready)throw new Error('Java API did not become healthy.');
  const env={MERIDIAN_API_TARGET:`http://127.0.0.1:${apiPort}`};
  server(process.execPath,['node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',String(webPort),'--strictPort'],web,{...env,VITE_API_BASE_URL:'/api/v1'});
  server(process.execPath,['node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',String(mobilePort),'--strictPort'],mobile,env);
  console.log(`\nConnected rehearsal\nWeb: http://127.0.0.1:${webPort}\nMobile companion: http://127.0.0.1:${mobilePort}\nJava API: http://127.0.0.1:${apiPort}/api/v1\nShared room: meridian-rehearsal\nCtrl+C stops only these processes.\n`);
} catch(error){console.error(error.message);stop(1);}
