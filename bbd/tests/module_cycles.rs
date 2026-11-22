// Copyright (C) 2026 Mercury Li
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License version 3 as
// published by the Free Software Foundation.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

type Module = Vec<String>;
type Imports = BTreeMap<Module, Vec<Module>>;
type Graph = BTreeMap<Module, BTreeSet<Module>>;

#[test]
fn daemon_modules_have_no_dependency_cycles() {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut imports = Imports::new();
    scan_file(&src.join("main.rs"), &src, &mut Vec::new(), &mut imports);

    if let Some(cycle) = find_cycle(&sibling_graph(&imports)) {
        let names: Vec<_> = cycle.iter().map(|module| module.join("::")).collect();
        panic!("daemon module dependency cycle: {}", names.join(" -> "));
    }
}

fn scan_file(file: &Path, dir: &Path, module: &mut Module, imports: &mut Imports) {
    let source = fs::read_to_string(file).unwrap_or_else(|err| panic!("{}: {err}", file.display()));
    scan(&tokens(&source), dir, module, imports);
}

// Follow module declarations from main.rs so out-of-line test modules are
// excluded just like inline ones. Follow source paths before name resolution,
// so references to items on a parent module do not turn into sibling imports.
fn scan(mut input: &[&str], dir: &Path, module: &mut Module, imports: &mut Imports) {
    imports.entry(module.clone()).or_default();
    let mut test_only = false;
    while !input.is_empty() {
        match input {
            ["#", rest @ ..] | ["#", "!", rest @ ..] if rest.first() == Some(&"[") => {
                let end = after_group(rest);
                test_only |= matches!(
                    &rest[..end],
                    ["[", "cfg", "(", "test", ")", "]"] | ["[", "test", "]"]
                );
                input = &rest[end..];
            }
            _ if test_only => {
                input = skip_item(input);
                test_only = false;
            }
            ["mod", name, delimiter @ (";" | "{"), rest @ ..] => {
                module.push((*name).to_owned());
                let child_dir = dir.join(name);
                if *delimiter == "{" {
                    let group = &input[2..];
                    let end = after_group(group);
                    scan(&group[1..end - 1], &child_dir, module, imports);
                    input = &group[end..];
                } else {
                    let flat = dir.join(format!("{name}.rs"));
                    let path = if flat.is_file() {
                        flat
                    } else {
                        child_dir.join("mod.rs")
                    };
                    scan_file(&path, &child_dir, module, imports);
                    input = rest;
                }
                module.pop();
            }
            ["use", rest @ ..] if rest.first() != Some(&"<") => {
                let end = rest.iter().position(|&part| part == ";").unwrap();
                use_tree(
                    &mut &rest[..end],
                    &mut Vec::new(),
                    module,
                    imports.get_mut(module).unwrap(),
                );
                input = &rest[end + 1..];
            }
            ["crate" | "super" | "self", "::", ..] => {
                let mut path = Vec::new();
                path_prefix(&mut input, &mut path);
                let next = input.first().copied().unwrap_or_default();
                if let Some(target) = resolve_path(module, path.into_iter().chain([next])) {
                    imports.get_mut(module).unwrap().push(target);
                }
            }
            _ => input = &input[1..],
        }
    }
}

fn after_group(input: &[&str]) -> usize {
    let mut depth = 0;
    for (index, token) in input.iter().enumerate() {
        match *token {
            "(" | "[" | "{" => depth += 1,
            ")" | "]" | "}" => depth -= 1,
            _ => {}
        }
        if depth == 0 {
            return index + 1;
        }
    }
    panic!("unclosed delimiter in {input:?}");
}

fn skip_item<'a, 'src>(mut input: &'a [&'src str]) -> &'a [&'src str] {
    let item = match input {
        ["pub", "(", ..] => &input[1 + after_group(&input[1..])..],
        ["pub", rest @ ..] => rest,
        _ => input,
    };
    let is_import = item.first() == Some(&"use");
    while let Some(&token) = input.first() {
        match token {
            ";" | "," => return &input[1..],
            "{" if !is_import => return &input[after_group(input)..],
            "(" | "[" | "{" => input = &input[after_group(input)..],
            _ => input = &input[1..],
        }
    }
    input
}

fn use_tree<'a>(
    input: &mut &[&'a str],
    prefix: &mut Vec<&'a str>,
    module: &Module,
    imports: &mut Vec<Module>,
) {
    let start = prefix.len();
    path_prefix(input, prefix);
    match *input {
        ["{", rest @ ..] => {
            *input = rest;
            while !matches!(*input, ["}", ..]) {
                use_tree(input, prefix, module, imports);
                if let [",", rest @ ..] = *input {
                    *input = rest;
                }
            }
            *input = &input[1..];
        }
        [name, rest @ ..] => {
            *input = if let ["as", _, after_alias @ ..] = rest {
                after_alias
            } else {
                rest
            };
            if let Some(target) = resolve_path(module, prefix.iter().copied().chain([*name])) {
                imports.push(target);
            }
        }
        [] => {}
    }
    prefix.truncate(start);
}

fn path_prefix<'a>(input: &mut &[&'a str], prefix: &mut Vec<&'a str>) {
    while let [name, "::", rest @ ..] = *input {
        prefix.push(name);
        *input = rest;
    }
}

fn resolve_path<'a>(module: &Module, path: impl Iterator<Item = &'a str>) -> Option<Module> {
    let mut path = path.peekable();
    let mut target = match path.peek() {
        Some(&"crate") => {
            path.next();
            Vec::new()
        }
        _ => module.clone(),
    };
    while let Some(&"super") = path.peek() {
        target.pop()?;
        path.next();
    }
    target.extend(
        path.take_while(|part| *part != "*")
            .filter(|part| *part != "self")
            .map(str::to_owned),
    );
    Some(target)
}

fn sibling_graph(imports: &Imports) -> Graph {
    let mut graph = Graph::new();
    for (from, paths) in imports {
        for path in paths {
            let mut to = path.as_slice();
            while !to.is_empty() && !imports.contains_key(to) {
                to = &to[..to.len() - 1];
            }
            // An edge between descendants also couples their enclosing siblings.
            if let Some(depth) = from.iter().zip(to).position(|(a, b)| a != b) {
                graph
                    .entry(from[..=depth].to_vec())
                    .or_default()
                    .insert(to[..=depth].to_vec());
            }
        }
    }
    graph
}

fn find_cycle(graph: &Graph) -> Option<Vec<Module>> {
    let mut done = BTreeSet::new();
    let mut active = Vec::new();
    graph
        .keys()
        .find_map(|node| visit(graph, node, &mut active, &mut done))
}

fn visit(
    graph: &Graph,
    node: &Module,
    active: &mut Vec<Module>,
    done: &mut BTreeSet<Module>,
) -> Option<Vec<Module>> {
    if let Some(start) = active.iter().position(|part| part == node) {
        let mut cycle = active[start..].to_vec();
        cycle.push(node.clone());
        return Some(cycle);
    }
    if !done.insert(node.clone()) {
        return None;
    }
    active.push(node.clone());
    let cycle = graph
        .get(node)
        .into_iter()
        .flatten()
        .find_map(|child| visit(graph, child, active, done));
    active.pop();
    cycle
}

fn tokens(mut source: &str) -> Vec<&str> {
    let mut result = Vec::new();
    while let Some(first) = source.chars().next() {
        if first.is_whitespace() {
            source = source.trim_start();
        } else if source.starts_with("//") {
            source = &source[source.find('\n').unwrap_or(source.len())..];
        } else if source.starts_with("/*") {
            source = after_comment(source);
        } else if let Some(rest) = after_literal(source) {
            result.push("<literal>");
            source = rest;
        } else {
            let len = if first.is_alphanumeric() || first == '_' {
                source
                    .find(|c: char| !c.is_alphanumeric() && c != '_')
                    .unwrap_or(source.len())
            } else if source.starts_with("::") {
                2
            } else {
                first.len_utf8()
            };
            result.push(&source[..len]);
            source = &source[len..];
        }
    }
    result
}

fn after_comment(mut source: &str) -> &str {
    let mut depth = 1;
    source = &source[2..];
    while depth > 0 {
        let marker = source.find(['/', '*']).expect("unclosed comment");
        source = &source[marker..];
        if source.starts_with("/*") {
            depth += 1;
            source = &source[2..];
        } else if source.starts_with("*/") {
            depth -= 1;
            source = &source[2..];
        } else {
            source = &source[1..];
        }
    }
    source
}

fn after_literal(source: &str) -> Option<&str> {
    let literal = source.strip_prefix(['b', 'c']).unwrap_or(source);
    if let Some(raw) = literal.strip_prefix('r') {
        let hashes = raw.len() - raw.trim_start_matches('#').len();
        let body = raw[hashes..].strip_prefix('"')?;
        let end = format!("\"{}", &raw[..hashes]);
        return Some(&body[body.find(&end).expect("unclosed raw string") + end.len()..]);
    }
    if let Some(body) = literal.strip_prefix('"') {
        return Some(after_quote(body, '"'));
    }
    let body = literal.strip_prefix('\'')?;
    if body.starts_with('\\') {
        return Some(after_quote(body, '\''));
    }
    // A lifetime has a leading quote but no closing quote after one character.
    body.get(body.chars().next()?.len_utf8()..)?
        .strip_prefix('\'')
}

fn after_quote(body: &str, quote: char) -> &str {
    let mut escaped = false;
    for (index, ch) in body.char_indices() {
        if !escaped && ch == quote {
            return &body[index + ch.len_utf8()..];
        }
        escaped = !escaped && ch == '\\';
    }
    panic!("unclosed literal");
}

#[test]
fn detects_cycles_between_siblings_of_any_depth() {
    let source = r"
        mod parent {
            mod a {
                mod child {
                    use super::super::{b::{self as sibling, some_item}, some_parent_item};
                }
            }
            mod b {
                use crate::parent::c::some_item;
            }
            mod c {
                fn f() { super::a::some_item(); }
            }
        }
    ";
    assert_eq!(
        fixture_cycle(source).unwrap(),
        ["parent::a", "parent::b", "parent::c", "parent::a"],
    );
}

#[test]
fn ignores_test_only_dependencies_and_literals() {
    let source = r##"
        mod a {
            use crate::b;
        }
        mod b {
            #[cfg(test)]
            mod alternate_tests {
                use crate::a;
            }
            #[cfg(test)]
            mod external_tests;
            #[cfg(test)]
            pub(crate) use crate::{a as renamed};
            #[test]
            fn checks_a() { super::a::run(); }
            const EXAMPLE: &str = r#" " use crate::a; " "#;
            /* Nested comment /* use crate::a; */ use crate::a; */
        }
    "##;
    assert_eq!(fixture_cycle(source), None);
}

fn fixture_cycle(source: &str) -> Option<Vec<String>> {
    let mut imports = Imports::new();
    scan(
        &tokens(source),
        Path::new(""),
        &mut Vec::new(),
        &mut imports,
    );
    find_cycle(&sibling_graph(&imports))
        .map(|cycle| cycle.iter().map(|module| module.join("::")).collect())
}
