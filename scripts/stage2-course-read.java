package com.training;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Reuses the isolated Auth/M01 course fixture; no server, production data, or network. */
final class Stage2CourseReadTest {
    private static int checks;
    private interface Work { void run() throws Exception; }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);checks++;}
    private static void reject(int status,Work work,String message) throws Exception {
        try {work.run();throw new AssertionError("Expected rejection: "+message);}
        catch(Api.ApiException e) {check(e.code==status,message+" ("+e.code+")");}
    }
    private static Object fixture(String name,Class<?>[] types,Object... args) throws Exception {
        Method method=M04CourseMaintenanceTest.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try {return method.invoke(null,args);}catch(InvocationTargetException e) {if(e.getCause() instanceof Exception cause)throw cause;throw e;}
    }
    private static Object field(String name) throws Exception {Field f=M04CourseMaintenanceTest.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static Auth.Session session(String name) throws Exception {Object actor=field(name);Method method=actor.getClass().getDeclaredMethod("session");method.setAccessible(true);return (Auth.Session)method.invoke(actor);}
    private static Map<String,Object> course(String code,String name,boolean active) {return new LinkedHashMap<>(Map.of("course_code",code,"course_name",name,"active",active));}
    private static String preview(long version,String label,List<Map<String,Object>> courses) throws Exception {
        return (String)fixture("preview",new Class<?>[]{long.class,String.class,List.class},version,label,courses);
    }
    private static void publish(long version,String label,List<Map<String,Object>> courses) throws Exception {
        String batch=preview(version,label,courses);fixture("confirm",new Class<?>[]{String.class,long.class},batch,version);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Pass new empty synthetic data directory");
        fixture("setup",new Class<?>[]{Path.class},Path.of(args[0]).toAbsolutePath());
        try {
            long scope=(Long)field("scope"),other=(Long)field("otherScope");Auth.Session reader=session("reader"),manager=session("manager");
            check(CourseCatalogIntegration.readCourses(reader,scope).isEmpty(),"Zero version returns empty course facts");
            reject(404,()->CourseCatalogIntegration.readCourse(reader,scope,"001"),"Zero version does not invent a course");
            List<Map<String,Object>> first=new ArrayList<>(List.of(course("001","同名课程",true),course("002","同名课程",true)));
            String staged=preview(0,"daily-first",first);
            check(CourseCatalogIntegration.readCourses(reader,scope).isEmpty(),"Unconfirmed preview is not published course evidence");
            fixture("confirm",new Class<?>[]{String.class,long.class},staged,0L);
            List<Map<String,Object>> published=CourseCatalogIntegration.readCourses(reader,scope);
            check(published.size()==2,"Partial first directory publishes two explicit courses");
            Map<String,Object> original=CourseCatalogIntegration.readCourse(reader,scope,"001");
            check(original.keySet().equals(Set.of("scope_id","organization_code","version","catalog_version","course_code","course_name","active","snapshot_id")),"Course facts expose exact documented fields only");
            check(original.get("scope_id").equals(scope)&&"001".equals(original.get("course_code"))&&"001".equals(original.get("organization_code")),"Stable scope/code preserve leading zeros");
            check(((String)original.get("snapshot_id")).matches("[a-f0-9]{64}"),"Published evidence has stable SHA-256 digest");
            check(!original.get("snapshot_id").equals(published.get(1).get("snapshot_id")),"Equal names do not merge different business codes");
            Map<String,Object> detached=CourseCatalogIntegration.readCourse(reader,scope,"001");detached.put("course_name","client mutation");published.clear();
            check(original.equals(CourseCatalogIntegration.readCourse(reader,scope,"001")),"Returned copies cannot mutate stored course evidence");
            reject(404,()->CourseCatalogIntegration.readCourse(reader,scope,"1"),"Numeric-looking code is not normalized");
            reject(400,()->CourseCatalogIntegration.readCourse(reader,scope,"同名课程"),"Course name cannot substitute for business code");
            reject(403,()->CourseCatalogIntegration.readCourses(manager,scope),"Manage-only does not gain protected catalog.read");
            reject(403,()->CourseCatalogIntegration.readCourse(reader,other,"001"),"Cross-organization read denied");
            reject(401,()->CourseCatalogIntegration.readCourses(null,scope),"Missing session denied");
            first.get(0).put("course_name","明确更名");first.get(0).put("active",false);publish(1,"daily-second",first);
            Map<String,Object> current=CourseCatalogIntegration.readCourse(reader,scope,"001"),historical=CourseCatalogIntegration.readCourse(reader,scope,1,"001");
            check(current.get("course_code").equals(original.get("course_code"))&&current.get("scope_id").equals(original.get("scope_id")),"Rename and deactivation retain stable identity");
            check(Boolean.FALSE.equals(current.get("active"))&&"明确更名".equals(current.get("course_name")),"Current exact lookup includes inactive course facts");
            check(original.equals(historical),"Historical lookup retains exact original name/status/version/digest");
            check(!current.get("snapshot_id").equals(historical.get("snapshot_id")),"Changed publication has distinct evidence digest");
            reject(404,()->CourseCatalogIntegration.readCourse(reader,scope,3,"001"),"Unpublished future revision is not guessed");
            reject(404,()->CourseCatalogIntegration.readCourse(reader,scope,0,"001"),"Version zero is not a published course");
            reject(404,()->CourseCatalogIntegration.readCourse(reader,scope,1,"MISSING"),"Historical missing code never falls back");
            reject(403,()->CourseCatalogIntegration.readCourse(reader,other,1,"001"),"Historical read checks scope permissions before version");
            Map<String,Object> saved=Db.one("SELECT payload FROM m04_catalog_revisions WHERE scope_id=? AND version=1",scope);
            Db.exec("UPDATE m04_catalog_revisions SET payload='{' WHERE scope_id=? AND version=1",scope);
            try {reject(409,()->CourseCatalogIntegration.readCourse(reader,scope,1,"001"),"Corrupt historical evidence fails closed");}
            finally {Db.exec("UPDATE m04_catalog_revisions SET payload=? WHERE scope_id=? AND version=1",saved.get("payload"),scope);}
            check(Db.count("m04_teacher_bindings")==0,"Course publications never create lecturer bindings");
            check(Json.parseMap((String)Db.one("SELECT payload FROM m04_catalog_revisions WHERE scope_id=? AND version=2",scope).get("payload")).get("certifications").equals(List.of()),"Course publications never create formal certification");
            Auth.revokeUserSessions(reader.uid);reject(401,()->CourseCatalogIntegration.readCourses(reader,scope),"Revoked cached session cannot use helper");
            System.out.println("Stage2CourseRead: "+checks+" checks passed (synthetic H2, real Auth/M01, no HTTP server)");
        } finally {Db.exec("SHUTDOWN");}
    }
}
