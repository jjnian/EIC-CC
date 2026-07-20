# -*- coding: utf-8 -*-
"""
Service 抽接口转换器。
策略：接口名 = 原类名，放原位置原包；实现类移到 impl/ 子包，改名 XxxServiceImpl。
因为接口名=原类名且在原位，外部 import package.XxxService 自动解析到接口，注入点无需改名。

用法:
  python transform_services.py <Service.java 绝对路径> [--apply]   # 单文件
  python transform_services.py --all [--apply]                     # 全部 @Service
"""
import re, os, sys

ROOT = r"D:\Code\EIC-CC\backend\src\main\java\com\tuiyan\backend\service"


def parse_package_imports(txt):
    package = re.search(r"^package\s+([\w.]+);", txt, re.M).group(1)
    imports = re.findall(r"^import\s+([\w.]+);", txt, re.M)
    return package, imports


def skip_body(lines, start):
    """从 start 行（含 { 的行）开始，跳过整个方法体，返回方法体结束后的下一行索引。"""
    depth = 0
    started = False
    i = start
    while i < len(lines):
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            started = True
        if started and depth <= 0:
            return i + 1
        i += 1
    return i


def extract_members(lines, class_name):
    """提取顶层 public 方法签名(排除构造器) + public 嵌套类型块 + public 常量。"""
    methods, nested, consts = [], [], []
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        sf = line.rstrip("\n")
        if not re.match(r"^    public\s+", sf):
            i += 1
            continue
        after = sf[4:].lstrip()  # 去掉 "    public "
        # 嵌套类型 record/interface/enum/class
        nm = re.match(r"(final\s+|abstract\s+|static\s+)*(record|interface|enum|class)\s+(\w+)", after)
        if nm:
            kind, tname = nm.group(2), nm.group(3)
            block = [sf]
            depth = sf.count("{") - sf.count("}")
            j = i
            while depth > 0 and j + 1 < n:
                j += 1
                block.append(lines[j].rstrip("\n"))
                depth += lines[j].count("{") - lines[j].count("}")
            nested.append((kind, tname, "\n".join(block)))
            i = j + 1
            continue
        # 构造器（方法名 == 类名）：跳过其声明 + 方法体
        if re.match(re.escape(class_name) + r"\s*\(", after):
            if "{" in sf:
                i = skip_body(lines, i)
            else:
                j = i
                while j < n and "{" not in lines[j]:
                    j += 1
                i = skip_body(lines, j) if j < n else j + 1
            continue
        # public static final 常量（单行）
        cm = re.match(r"static\s+final\s+.+?=\s*.+;", after)
        if cm:
            consts.append(sf.strip())
            i += 1
            continue
        # 方法签名：收集到 { 或 ;
        sig_lines = [sf]
        terminator = "{" if "{" in sf else (";" if re.search(r";\s*$", sf) else None)
        j = i
        while terminator is None and j + 1 < n:
            j += 1
            sig_lines.append(lines[j].rstrip("\n"))
            if "{" in lines[j]:
                terminator = "{"; break
            if re.search(r";\s*$", lines[j]):
                terminator = ";"; break
        sig = " ".join(s.strip() for s in sig_lines)
        if "{" in sig:
            sig = sig[:sig.index("{")].rstrip()
        elif ";" in sig:
            sig = sig[:sig.index(";")].rstrip()
        sig = sig.rstrip()
        if sig:
            methods.append(sig)
        # 跳过方法体
        if terminator == "{":
            k = i
            while k <= j and "{" not in lines[k]:
                k += 1
            i = skip_body(lines, k)
        else:
            i = j + 1
    return methods, nested, consts


def build_interface(class_name, package, imports, methods, nested, consts, javadoc):
    lines = [f"package {package};", ""]
    for imp in imports:
        if imp.endswith(".Service") or "stereotype.Service" in imp:
            continue
        lines.append(f"import {imp};")
    lines.append("")
    lines.append((javadoc.rstrip() if javadoc else f"/** {class_name} 服务接口。 */"))
    lines.append(f"public interface {class_name} {{")
    lines.append("")
    for kind, tname, block in nested:
        for bl in block.split("\n"):
            lines.append("    " + bl if not bl.startswith("    ") else bl)
        lines.append("")
    for c in consts:
        c2 = re.sub(r"^public\s+static\s+final\s+", "", c)
        lines.append("    " + c2)
    if consts:
        lines.append("")
    for sig in methods:
        sig2 = re.sub(r"^public\s+synchronized\s+", "", sig)
        sig2 = re.sub(r"^public\s+", "", sig2)
        lines.append("    " + sig2 + ";")
        lines.append("")
    lines.append("}")
    return "\n".join(lines) + "\n"


def build_impl(class_name, package, txt):
    new_pkg = package + ".impl"
    txt2 = re.sub(r"^package\s+[\w.]+;", f"package {new_pkg};", txt, count=1, flags=re.M)
    # class 声明改名 + implements
    decl_re = r"(public\s+(?:final\s+|abstract\s+)?class\s+)" + re.escape(class_name) + r"\b"

    def repl(m):
        prefix = m.group(1)
        rest = txt2[m.end():m.end() + 200]
        # 判断是否已有 implements（需先跳过 extends）
        ext = re.match(r"\s*extends\s+[\w.<>,\s]+?(\s*implements\b|\s*\{)", rest, re.S)
        if ext and "implements" in ext.group(1):
            return prefix + class_name + "Impl"
        if ext and "{" in ext.group(1):
            # 只有 extends，无 implements
            return prefix + class_name + "Impl" + " implements " + class_name
        # 无 extends
        has_impl = rest.lstrip().startswith("implements")
        if has_impl:
            return prefix + class_name + "Impl"
        return prefix + class_name + "Impl" + " implements " + class_name

    txt2 = re.sub(decl_re, repl, txt2, count=1)
    # 构造器名改为 *Impl
    txt2 = re.sub(r"(^|\n)(\s*)public\s+" + re.escape(class_name) + r"\s*\(",
                  lambda m: m.group(1) + m.group(2) + "public " + class_name + "Impl(", txt2)
    # 加 import 接口
    iface_imp = f"import {package}.{class_name};"
    if iface_imp not in txt2:
        txt2 = re.sub(r"(^import\s)", iface_imp + "\n\\1", txt2, count=1, flags=re.M)
    return txt2


def process(path, apply=False, verbose=True):
    class_name = os.path.splitext(os.path.basename(path))[0]
    with open(path, encoding="utf-8") as f:
        txt = f.read()
    lines = txt.splitlines(keepends=False)
    package, imports = parse_package_imports(txt)
    methods, nested, consts = extract_members(lines, class_name)
    # 类级 javadoc
    cm = re.search(r"(/\*\*.*?\*/)\s*^public\s+(?:final\s+|abstract\s+)?class\s+" + re.escape(class_name),
                   txt, re.M | re.S)
    javadoc = cm.group(1) if cm else None

    iface_text = build_interface(class_name, package, imports, methods, nested, consts, javadoc)
    impl_text = build_impl(class_name, package, txt)

    src_dir = os.path.dirname(path)
    iface_path = path
    impl_dir = os.path.join(src_dir, "impl")
    impl_path = os.path.join(impl_dir, class_name + "Impl.java")

    if verbose or not apply:
        print(f"=== {class_name} (pkg {package}) methods={len(methods)} nested={len(nested)} consts={len(consts)}")

    if apply:
        os.makedirs(impl_dir, exist_ok=True)
        with open(iface_path, "w", encoding="utf-8") as f:
            f.write(iface_text)
        with open(impl_path, "w", encoding="utf-8") as f:
            f.write(impl_text)
        print(f"  [APPLIED] iface+impl")
    return iface_text, impl_text


def find_all_services():
    out = []
    for dp, _, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            # 跳过 impl 子包（避免重复处理已转换的）
            if os.sep + "impl" in dp or dp.endswith("impl"):
                continue
            p = os.path.join(dp, fn)
            with open(p, encoding="utf-8") as f:
                if "@Service" in f.read():
                    out.append(p)
    return out


if __name__ == "__main__":
    args = sys.argv[1:]
    apply = "--apply" in args
    positional = [a for a in args if not a.startswith("--")]

    if "--all" in args:
        services = find_all_services()
        print(f"发现 {len(services)} 个 @Service 类（不含 impl/）")
        for p in services:
            try:
                process(p, apply=apply, verbose=True)
            except Exception as e:
                print(f"  [ERROR] {os.path.basename(p)}: {e}")
                raise
    else:
        if not positional:
            print("用法: python transform_services.py <file.java> [--apply]  或  --all [--apply]")
            sys.exit(1)
        process(positional[0], apply=apply, verbose=True)
