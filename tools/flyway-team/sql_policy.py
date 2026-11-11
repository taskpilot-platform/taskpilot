"""Fail-closed PostgreSQL 17 AST policy for NEW migrations, not a SQL sandbox.

Accepted history is immutable and is never rewritten to satisfy this policy.
Procedural/dynamic SQL and unrecognised statement forms need a policy review,
not a comment, filename exemption, or regex-based bypass.
"""


class SqlPolicyError(ValueError):
    pass


RELATION_TARGETS = {
    "CreateStmt": "relation", "IndexStmt": "relation", "AlterTableStmt": "relation",
    "CreateSeqStmt": "sequence", "AlterSeqStmt": "sequence", "ViewStmt": "view",
    "InsertStmt": "relation", "UpdateStmt": "relation", "DeleteStmt": "relation",
}
SUPPORTED = set(RELATION_TARGETS) | {
    "CreateEnumStmt", "AlterEnumStmt", "CreateTableAsStmt", "DropStmt",
    "CommentStmt", "SelectStmt", "VariableSetStmt",
}
FUNCTIONS = {
    "lower", "upper", "btrim", "ltrim", "rtrim", "length", "char_length",
    "replace", "concat", "concat_ws", "now", "to_char", "to_timestamp",
    "abs", "round", "ceil", "floor", "greatest", "least", "count", "sum",
    "min", "max", "avg", "jsonb_build_object", "jsonb_build_array",
}


def validate(sql):
    try:
        from pglast import ast, parse_sql
        from pglast.enums import ObjectType
    except ImportError:
        raise SqlPolicyError("install tools/flyway-team/requirements.txt (PostgreSQL 17 parser required)") from None
    try:
        statements = parse_sql(sql)
    except Exception:
        raise SqlPolicyError("PostgreSQL parser rejected SQL; details omitted") from None
    if not statements:
        raise SqlPolicyError("empty migration")

    def walk(value, visible_ctes=frozenset()):
        if isinstance(value, ast.Node):
            yield value, visible_ctes
            # WITH bindings belong to their query, not every nested query in
            # the statement. Non-recursive items see only earlier siblings.
            clause = getattr(value, "withClause", None)
            if clause is not None:
                current = set(visible_ctes)
                if clause.recursive:
                    current.update(item.ctename for item in clause.ctes)
                for item in clause.ctes:
                    yield from walk(item, frozenset(current))
                    current.add(item.ctename)
                visible_ctes = frozenset(current)
            for member in value:
                if member != "withClause":
                    yield from walk(getattr(value, member), visible_ctes)
        elif isinstance(value, (tuple, list)):
            for child in value:
                yield from walk(child, visible_ctes)

    def name(parts, length=2):
        values = tuple(item.sval for item in (parts or ()) if isinstance(item, ast.String))
        if len(values) != length or len(values) != len(parts or ()) or values[0] != "public":
            raise SqlPolicyError("object target must explicitly use public schema")

    def relation(value):
        if not isinstance(value, ast.RangeVar) or value.schemaname != "public" or value.catalogname:
            raise SqlPolicyError("relation target must explicitly use public schema")
        if value.relpersistence == "t":
            raise SqlPolicyError("temporary relations are outside the supported migration policy")

    allowed_objects = {ObjectType.OBJECT_TABLE, ObjectType.OBJECT_INDEX, ObjectType.OBJECT_SEQUENCE,
                       ObjectType.OBJECT_TYPE, ObjectType.OBJECT_VIEW, ObjectType.OBJECT_MATVIEW}
    for raw in statements:
        statement = raw.stmt
        kind = type(statement).__name__
        if kind not in SUPPORTED:
            raise SqlPolicyError("unsupported statement: " + kind + "; review policy before use")
        if kind in RELATION_TARGETS:
            relation(getattr(statement, RELATION_TARGETS[kind]))
        elif kind in {"CreateEnumStmt", "AlterEnumStmt"}:
            name(statement.typeName)
        elif kind == "CreateTableAsStmt":
            relation(statement.into.rel)
        elif kind == "DropStmt":
            if statement.removeType not in allowed_objects:
                raise SqlPolicyError("unsupported DROP object kind")
            for obj in statement.objects:
                name(obj)
        elif kind == "CommentStmt":
            if statement.objtype == ObjectType.OBJECT_COLUMN:
                name(statement.object, 3)
            elif statement.objtype in allowed_objects:
                name(statement.object)
            else:
                raise SqlPolicyError("unsupported COMMENT object kind")
        elif kind == "VariableSetStmt":
            if not statement.is_local or statement.name not in {"lock_timeout", "statement_timeout"}:
                raise SqlPolicyError("only SET LOCAL lock_timeout/statement_timeout is supported")

        for node, ctes in walk(statement):
            node_kind = type(node).__name__
            # Nested DML in CTEs must still have an explicit target.
            if node_kind in RELATION_TARGETS:
                relation(getattr(node, RELATION_TARGETS[node_kind]))
            if isinstance(node, ast.RangeVar):
                if node.schemaname is None and node.relname in ctes:
                    continue
                relation(node)
            elif isinstance(node, ast.IntoClause):
                relation(node.rel)
            elif isinstance(node, ast.DefElem) and node.defname == "owned_by":
                name(node.arg, 3)
            elif isinstance(node, ast.DefElem) and node.defname == "sequence_name":
                name(node.arg)
            elif isinstance(node, ast.FuncCall):
                names = tuple(part.sval for part in node.funcname)
                if names[-1] not in FUNCTIONS or (len(names) > 1 and names[:-1] != ("pg_catalog",)):
                    raise SqlPolicyError("function outside reviewed builtin allowlist")
            elif node_kind in {"DoStmt", "CallStmt", "CreateFunctionStmt", "AlterObjectSchemaStmt",
                               "ExecuteStmt", "MergeStmt"}:
                raise SqlPolicyError("procedural SQL/schema redirection requires explicit policy review")
