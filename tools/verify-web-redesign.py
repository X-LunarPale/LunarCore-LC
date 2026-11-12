"""Read-only browser acceptance on the actual test-server URL; credentials remain in memory."""
import runpy, sys, json
from pathlib import Path
from playwright.sync_api import sync_playwright
sys.argv = ['test-server-control.py', 'status']
ctx = runpy.run_path(str(Path(__file__).with_name('test-server-control.py')))
base, token = ctx['base'], ctx['token']
out = Path(__file__).resolve().parents[1] / 'artifacts' / 'web-redesign'
out.mkdir(parents=True, exist_ok=True)
errors=[]
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless=True)
    context=browser.new_context(viewport={'width':1600,'height':1000},device_scale_factor=1)
    context.add_init_script('(value => { if (location.origin === '+json.dumps(base)+') localStorage.setItem("lc-token",value); })('+json.dumps(token)+')')
    page=context.new_page()
    page.on('pageerror',lambda error:errors.append(str(error)))
    panels=ctx['call']('/api/panels')
    paths=[('dashboard','/'),('modules','/modules'),('plugins','/plugins')]+[(ns,'/panel/'+ns+'/'+next(panel['id'] for panel in panels if panel['namespace']==ns)) for ns in ('signin','performance','playertitle','permissions')]
    signin_path=next(path for name,path in paths if name=='signin')
    for name,path in paths:
        page.goto(base+path,wait_until='networkidle')
        page.locator('.page').wait_for()
        page.screenshot(path=str(out/(name+'.png')),full_page=False)
        assert page.locator('body').evaluate('(el)=>el.scrollWidth<=innerWidth+1'),name+' horizontal overflow'
        print(name,'rendered',page.locator('.page-title').all_text_contents())
        if name=='plugins':
            with page.expect_response(lambda r: r.url.endswith('/api/plugins') and r.request.method=='GET',timeout=8000):
                pass
            print('Automatic plugin refresh verified without manual action')
        if name=='permissions':
            page.get_by_role('button',name='创建权限组',exact=True).click()
            page.locator('.panel-action-dialog').wait_for()
            page.screenshot(path=str(out/'action-dialog.png'))
            page.get_by_role('button',name='取消',exact=True).click()
        if name=='signin':
            fields=page.locator('.panel-form input:not([type=hidden])')
            if fields.count():
                original=fields.first.input_value();fields.first.fill(original+'test')
                with page.expect_response(lambda r: '/api/ext/signin/' in r.url and r.request.method=='GET',timeout=16000):
                    pass
                assert fields.first.input_value()==original+'test','Auto refresh overwrote unsaved input'
                page.get_by_role('button',name='重新加载',exact=True).first.click()
                page.get_by_role('button',name='继续编辑',exact=True).click()
                assert fields.first.input_value()==original+'test'
                fields.first.fill(original)
    for path in ('/players','/console','/server','/audit','/config','/accounts','/profile','/does-not-exist'):
        page.goto(base+path,wait_until='networkidle')
        assert page.locator('main').is_visible(),path
    print('Core routes and unsaved-change guard verified')
    page.get_by_label('搜索管理功能').fill('称号')
    assert page.locator('.menu-title').count()>=1
    page.get_by_label('搜索管理功能').fill('')
    page.set_viewport_size({'width':390,'height':844})
    page.goto(base+signin_path,wait_until='networkidle')
    assert page.locator('body').evaluate('(el)=>el.scrollWidth<=innerWidth+1')
    page.screenshot(path=str(out/'mobile-signin.png'))
    page.get_by_label('展开或折叠菜单').click()
    page.wait_for_timeout(350)
    assert page.locator('.aside').bounding_box()['x']>=-1
    page.screenshot(path=str(out/'mobile-navigation.png'))
    page.get_by_label('关闭导航').click(force=True)
    page.wait_for_timeout(350)
    page.set_viewport_size({'width':1600,'height':1000})
    page.get_by_label('切换到暗色').click()
    page.wait_for_timeout(400)
    antixray_path='/panel/antixray/'+next(panel['id'] for panel in panels if panel['namespace']=='antixray')
    page.goto(base+antixray_path,wait_until='networkidle')
    page.wait_for_timeout(400)
    nav=page.locator('.aside').evaluate('(e)=>({ink:getComputedStyle(e).getPropertyValue("--nav-ink").trim(),line:getComputedStyle(e).getPropertyValue("--nav-line").trim()})')
    assert nav['ink']=='#b7c5d7',nav
    assert nav['line']=='#29384b',nav
    header=page.locator('.el-table th').first.evaluate('(e)=>getComputedStyle(e).backgroundColor')
    assert header=='rgb(26, 42, 64)',header
    print('Dark navigation and table tokens verified',nav,header)
    print('Dark computed:',page.locator('.card').first.evaluate('(e)=>({background:getComputedStyle(e).backgroundColor,surface:getComputedStyle(e).getPropertyValue("--lc-surface")})'))
    page.screenshot(path=str(out/'dark-signin.png'))
    page.emulate_media(reduced_motion='reduce')
    page.screenshot(path=str(out/'reduced-motion.png'))
    assert page.locator('.page').is_visible()
    page.goto(base+'/audit',wait_until='networkidle')
    assert page.locator('.audit-table .el-table__row--striped').count()==0
    assert page.locator('.page-title').inner_text()=='操作日志'
    page.screenshot(path=str(out/'audit-refined.png'))
    page.get_by_role('button',name='详情',exact=True).first.click()
    page.locator('.record-detail').wait_for()
    assert page.locator('.record-detail').inner_text()
    page.locator('.el-dialog__headerbtn').last.click()
    print('Audit refined layout and complete record dialog verified')
    context.close()
    guest=browser.new_context(viewport={'width':1440,'height':900})
    login=guest.new_page();login.goto(base+'/login',wait_until='networkidle')
    login.screenshot(path=str(out/'login.png'))
    assert login.locator('input').count()>=2
    browser.close()
assert not errors,errors
print('Browser acceptance passed; screenshots:',out)
