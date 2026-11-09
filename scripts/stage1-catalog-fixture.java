package com.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Synthetic setup only. All M04 provisioning/publication is exercised through the real HTTP host. */
final class Stage1CatalogHttpFixture {
    private static final String[] CATALOG_TABLES = {"m04_catalog_scopes", "m04_catalog_batches",
            "m04_catalog_revisions", "m04_teacher_bindings", "m04_catalog_events"};

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected loopback port or inspect");
        Path evidence = Path.of(required("stage1.catalog.evidence")).toRealPath();
        Path root = Path.of(required("stage1.catalog.run")).toRealPath();
        Path data = Path.of(required("data.dir")).toRealPath();
        if (!evidence.endsWith("yanxu-stage1/evidence/m04") || !root.getParent().equals(evidence)
                || !root.getFileName().toString().startsWith("http-") || !data.getParent().equals(root)
                || !data.getFileName().toString().equals("data")
                || !"127.0.0.1".equals(System.getProperty("bind.address"))
                || Boolean.getBoolean("bootstrap.demo") || !"".equals(System.getProperty("semantic.port"))
                || !"".equals(System.getProperty("semantic.token"))
                || !"".equals(System.getProperty("account.import.manifest"))
                || !"".equals(System.getProperty("account.import.manifest.sha256"))
                || !"disabled".equals(System.getenv("YANXU_LOGIN_MAIL_TRANSPORT")))
            throw new IllegalArgumentException("Explicit isolated stage1 evidence settings required");
        if ("inspect".equals(args[0])) {
            Map<String,Object> result = counts();
            result.put("scope_rows", Db.query("SELECT scope_id,organization_code,version FROM m04_catalog_scopes ORDER BY scope_id"));
            result.put("binding_rows", Db.query("SELECT scope_id,teacher_code,teacher_id,created_version FROM m04_teacher_bindings ORDER BY scope_id,teacher_code"));
            List<Object> revisions = new ArrayList<>();
            for (Map<String,Object> row : Db.query("SELECT scope_id,version,payload FROM m04_catalog_revisions ORDER BY scope_id,version")) {
                Map<?,?> catalog = (Map<?,?>) Json.parse((String) row.remove("payload"));
                row.put("courses", catalog.get("courses"));
                row.put("teachers", catalog.get("teachers"));
                row.put("certifications", catalog.get("certifications"));
                revisions.add(row);
            }
            result.put("revisions", revisions);
            System.out.println(Json.write(result));
            Db.get().close();
            return;
        }
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Fresh empty fixture data required");
        }
        String password = required("bootstrap.admin.password");
        if (!password.matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Random fixture password required");
        Db.init();
        if (OrganizationAccessStore.configuration() != null || Db.count("teachers") != 0)
            throw new IllegalStateException("Unexpected seeded identity or teachers");
        for (String table : CATALOG_TABLES)
            if (Db.count(table) != 0) throw new IllegalStateException("Unexpected seeded catalog");
        String hash = Auth.hash(password);
        Map<String,Long> accounts = new LinkedHashMap<>();
        for (String name : List.of("manager", "reader", "other", "colleague", "manageonly", "unbound")) {
            String role = Set.of("reader", "unbound").contains(name) ? "manager" : "viewer";
            accounts.put(name, Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                    "stage1-synthetic-" + name, hash, "STAGE1 SYNTHETIC " + name, role));
        }
        Set<String> roles = Set.of("MAINTAIN", "READER", "WRITEONLY");
        RelationRule optional = new RelationRule(false, roles, false, false);
        Configuration config = new Configuration("stage1-synthetic-identity-v1",
                new CodeRules("[0-9]{3}", "P[0-9]+", "[A-Z]+"), roles,
                List.of(new Organization("001", null, true), new Organization("002", null, true), new Organization("003", null, true)),
                List.of(person("P1", "001", "MAINTAIN"), person("P2", "001", "READER"),
                        person("P3", "002", "MAINTAIN"), person("P4", "001", "MAINTAIN"), person("P5", "003", "WRITEONLY")),
                List.of(new RoleRelations("MAINTAIN", optional, optional), new RoleRelations("READER", optional, optional),
                        new RoleRelations("WRITEONLY", optional, optional)),
                List.of(new AccountBinding(accounts.get("manager"), "P1", true), new AccountBinding(accounts.get("reader"), "P2", true),
                        new AccountBinding(accounts.get("other"), "P3", true), new AccountBinding(accounts.get("colleague"), "P4", true),
                        new AccountBinding(accounts.get("manageonly"), "P5", true)),
                List.of(new Grant("maintain-read", "MAINTAIN", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("maintain-write", "MAINTAIN", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("reader-read", "READER", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("writeonly-write", "WRITEONLY", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of())));
        OrganizationAccessStore.publish(Auth.get(Auth.login("admin", password)), null, config);
        // Distinct existing profiles deliberately share a display name. Free-text org is not an M01 ownership mapping.
        teacher("STAGE1 SYNTHETIC 同名教师", "001", "讲师", "浙江", "杭州");
        teacher("STAGE1 SYNTHETIC 同名教师", "002", "高级讲师", "浙江", "宁波");
        teacher("STAGE1 SYNTHETIC 第三教师", "SYNTHETIC free text", "讲师", "浙江", "杭州");
        teacher("STAGE1 SYNTHETIC 待核教师", "", "讲师", "浙江", "杭州");
        teacher("STAGE1 SYNTHETIC 缺资料教师", "SYNTHETIC incomplete", null, "", "");
        Map<String,Object> initial = counts();
        initial.put("fixture", "synthetic-only-initial-zero-scopes");
        Files.writeString(root.resolve("initial-state.json"), Json.write(initial));
        // The supplied null transport prohibits email while keeping real Main, Api, Auth and M01 handlers.
        Main.start(args, new NotificationChannelsLoginVerification(System::currentTimeMillis, null));
    }

    private static Map<String,Object> counts() throws Exception {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String table : CATALOG_TABLES) result.put(table, Db.count(table));
        result.put("organization_access_config", Db.count("organization_access_config"));
        result.put("teachers", Db.count("teachers"));
        return result;
    }
    private static void teacher(String name, String org, String level, String province, String city) throws Exception {
        Db.insert("INSERT INTO teachers(name,org,status,teacher_level,base_province,base_city) VALUES(?,?,?,?,?,?)",
                name, org, "在库", level, province, city);
    }
    private static Person person(String code, String org, String role) {
        return new Person(code, org, Set.of(), null, null, Set.of(role), true);
    }
    private static String required(String name) {
        String value = System.getProperty(name, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing isolated fixture setting: " + name);
        return value;
    }
}
