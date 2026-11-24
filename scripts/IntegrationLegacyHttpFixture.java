package com.training;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only bootstrap: explicit completed legacy fact, never an HTTP bypass or production migration. */
public final class IntegrationLegacyHttpFixture {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(System.getProperty("data.dir", "")).toAbsolutePath().normalize();
        if (!Boolean.getBoolean("integration.legacy.fixture") || !Boolean.getBoolean("bootstrap.demo")
                || !"127.0.0.1".equals(System.getProperty("bind.address")) || args.length != 1
                || !Files.isDirectory(data)) throw new IllegalArgumentException("Explicit fresh synthetic loopback fixture required");
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Synthetic fixture must start from an empty directory");
        }
        Db.init();
        if (Db.query("SELECT * FROM projects WHERE id=2 AND status='进行中' AND hours=16").size() != 1
                || !Db.query("SELECT * FROM dispatches WHERE project_id=2").isEmpty())
            throw new IllegalStateException("Unexpected demo seed; refusing to overwrite historical facts");
        Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,hours,status,sent_at,confirmed_at,msg_log,remark) VALUES(2,4,?,'2026-08-01','08:00','20:00',16,'已完成','2026-07-31','2026-07-31',?,?)",
                "历史问卷归档合成授课", "SYNTHETIC historical completed delivery", "Fixture only: unmanaged legacy completion, not a newly authorized confirmation");
        System.out.println("SYNTHETIC_LEGACY_SEED: completed project 2 delivery preserved without inventing organization mapping");
        Main.main(args);
    }
}
