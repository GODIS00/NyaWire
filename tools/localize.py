"""Extract Kotlin user-facing Russian strings into Android resources.

Run --scan to prepare the translation catalogue, then --apply after reviewing
English text in tools/translations.json. Kotlin templates become positional args.
Regex data is intentionally excluded. This script is a migration tool, not runtime.
"""
from pathlib import Path
import re, json, argparse, html

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'tools/translations.json'
CYR = re.compile('[А-Яа-яЁё]')
entries = json.loads(CATALOG.read_text('utf-8')) if CATALOG.exists() else []
by_ru = {e['ru']: e for e in entries}
applying = False

def unescape(s):
    table = {'n':'\n','r':'\r','t':'\t','b':'\b','f':'\f','"':'"',"'":"'",'\\':'\\','$':'$'}
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: chr(int(m[1][1:],16)) if m[1].startswith('u') else table.get(m[1],m[0]), s)

def block_end(src, start):
    depth, i = 1, start
    while i < len(src):
        if src.startswith('//',i):
            end=src.find('\n',i); i=len(src) if end<0 else end; continue
        if src.startswith('/*',i):
            end=src.find('*/',i+2); i=len(src) if end<0 else end+2; continue
        if src[i]=='"':
            i, _ = read_string(src,i); continue
        if src[i]=="'":
            i += 1
            while i<len(src):
                if src[i]=='\\': i+=2; continue
                if src[i]=="'": i+=1; break
                i+=1
            continue
        if src[i]=='{': depth+=1
        if src[i]=='}':
            depth-=1
            if depth==0: return i
        i+=1
    raise ValueError('Unclosed template')

def read_string(src, start):
    raw=src.startswith('"""',start); delim='"""' if raw else '"'
    i=start+len(delim); begin=i; parts=[]
    while i<len(src):
        if not raw and src[i]=='\\': i+=2; continue
        if src.startswith(delim,i):
            parts.append(('text',src[begin:i],raw)); return i+len(delim),parts
        if src[i]=='$' and i+1<len(src):
            if src[i+1]=='{':
                parts.append(('text',src[begin:i],raw)); end=block_end(src,i+2)
                parts.append(('expr',src[i+2:end],False)); i=end+1; begin=i; continue
            m=re.match(r'\$([A-Za-z_][A-Za-z_0-9]*)',src[i:])
            if m:
                parts.append(('text',src[begin:i],raw)); parts.append(('expr',m[1],False))
                i+=len(m[0]); begin=i; continue
        i+=1
    raise ValueError('Unclosed string')

def transform(src, filename):
    result=[]; i=0
    while i<len(src):
        if src.startswith('//',i):
            end=src.find('\n',i); end=len(src) if end<0 else end
            result.append(src[i:end]); i=end; continue
        if src.startswith('/*',i):
            end=src.find('*/',i+2); end=len(src) if end<0 else end+2
            result.append(src[i:end]); i=end; continue
        if src[i]=="'":
            begin=i; i+=1
            while i<len(src):
                if src[i]=='\\':i+=2;continue
                if src[i]=="'": i+=1;break
                i+=1
            result.append(src[begin:i]);continue
        if src[i]!='"': result.append(src[i]); i+=1;continue
        end,parts=read_string(src,i)
        regex = bool(re.search(r'Regex\s*\(\s*$',src[max(0,i-60):i]))
        has_ru=any(CYR.search(text) for kind,text,raw in parts if kind=='text')
        if regex:
            result.append(src[i:end]);i=end;continue
        if has_ru:
            args=[]; text=''
            for kind,value,raw in parts:
                if kind=='text': text+=value if raw else unescape(value)
                else:
                    args.append(transform(value,filename));text+=f'@@ARG{len(args)}@@'
            if args: text=text.replace('%','%%')
            text=re.sub(r'@@ARG(\d+)@@',lambda m:f'%{m[1]}$s',text)
            entry=by_ru.get(text)
            if entry is None:
                entry={'id':f'text_{len(entries)+1:03d}','ru':text,'en':'','source':filename}
                entries.append(entry);by_ru[text]=entry
            result.append('AppText.text(R.string.'+entry['id']+('' if not args else ', '+', '.join(args))+')')
        else:
            # Translate nested text inside templates without changing JSON/raw data.
            raw=src.startswith('"""',i); delim='"""' if raw else '"'
            result.append(delim+''.join(value if kind=='text' else '${'+transform(value,filename)+'}' for kind,value,_ in parts)+delim)
        i=end
    return ''.join(result)

def xml_value(value):
    return html.escape(value,quote=False).replace('\\','\\\\').replace("'","\\'").replace('"','\\"').replace('\n','\\n').replace('\t','\\t')

def main():
    global applying
    p=argparse.ArgumentParser();p.add_argument('--scan',action='store_true');p.add_argument('--apply',action='store_true');a=p.parse_args()
    applying=a.apply
    changed=[]
    for path in sorted((ROOT/'app/src/main/java').rglob('*.kt')):
        if path.name=='AppText.kt':continue
        src=path.read_text('utf-8');output=transform(src,str(path.relative_to(ROOT)).replace('\\','/'))
        if src!=output:
            if 'AppText.' in output:
                imports=[]
                if 'import com.gdhapp.client.R\n' not in output: imports.append('import com.gdhapp.client.R')
                if 'import com.gdhapp.client.i18n.AppText\n' not in output:imports.append('import com.gdhapp.client.i18n.AppText')
                if imports:output=re.sub(r'(package [^\n]+\n)',lambda m:m[1]+'\n'+'\n'.join(imports)+'\n',output,count=1)
            changed.append((path,output))
    CATALOG.write_text(json.dumps(entries,ensure_ascii=False,indent=2)+'\n','utf-8')
    if applying:
        missing=[e['id'] for e in entries if not e['en']]
        if missing:raise SystemExit('Missing English: '+', '.join(missing))
        for path,output in changed:path.write_text(output,'utf-8')
        for lang,folder in [('en','values'),('ru','values-ru')]:
            directory=ROOT/'app/src/main/res'/folder;directory.mkdir(exist_ok=True)
            strings=['<resources>']
            if lang == 'en': strings.append('    <string name="app_name" translatable="false">NyaWire</string>')
            strings.append('    <string name="vpn_channel">'+('VPN connection' if lang=='en' else 'VPN-подключение')+'</string>')
            for e in entries:
                strings.append(f'    <string name="{e["id"]}">'+xml_value(e.get('ru_display', e['ru']) if lang=='ru' else e[lang])+'</string>')
            strings.append('</resources>\n')
            (directory/'strings.xml').write_text('\n'.join(strings),'utf-8')
    print(f'{len(entries)} unique strings; {len(changed)} Kotlin files')
    if not a.apply:
        for e in entries: print(e['id']+' | '+e['ru'].replace('\n',' / '))

if __name__=='__main__':main()
