package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.ManagementSettlementAccounting.*;

/** Synthetic ledger fixture; real authorization and the existing Api.handle GET /projects projection. */
public final class DashboardSettlementReadFixture {
    static int checks;
    static final Map<String,Object> evidence=new LinkedHashMap<>();
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void eq(Object a,Object b,String message){check(Objects.equals(a,b),message+": "+a+" != "+b);}
    static void configure(String version,boolean demand,boolean delivery)throws Exception{
        Configuration base=M06SettlementBridgeTest.config(version,false,false,false);
        List<Grant> grants=new ArrayList<>();
        if(demand)grants.add(new Grant("DEMAND","OPERATOR","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(delivery)grants.add(new Grant("DELIVERY","OPERATOR","delivery.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        OrganizationAccessStore.publish(M06SettlementBridgeTest.admin,OrganizationAccessStore.configuration().version(),new Configuration(version,base.codeRules(),base.roleCodes(),base.organizations(),base.people(),base.relations(),base.accountBindings(),grants));
        M06SettlementBridgeTest.login();
    }
    static final class Exchange extends HttpExchange {
        final Headers request=new Headers(),response=new Headers();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final Map<String,Object> attributes=new HashMap<>();int status;
        Exchange(String token){request.set("X-Token",token);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return URI.create("/api/projects");}public String getRequestMethod(){return "GET";}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return new ByteArrayInputStream(new byte[0]);}public OutputStream getResponseBody(){return bytes;}public void sendResponseHeaders(int code,long size){status=code;}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public InetSocketAddress getLocalAddress(){return getRemoteAddress();}public int getResponseCode(){return status;}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
    static List<Map<String,Object>> read(String name)throws Exception{
        String before=M06SettlementBridgeTest.fingerprint();
        Exchange ex=new Exchange(M06SettlementBridgeTest.token);Api.handle(ex);eq(ex.status,200,"existing project list: "+ex.bytes.toString(StandardCharsets.UTF_8));
        Map<String,Object> result=obj(Json.parse(ex.bytes.toString(StandardCharsets.UTF_8)));eq(((Number)result.get("code")).intValue(),0,"success envelope");
        List<Map<String,Object>> projects=rows(result.get("data"));
        eq(M06SettlementBridgeTest.fingerprint(),before,"GET leaves all synthetic database tables unchanged");
        check(projects.stream().noneMatch(p->((Number)p.get("id")).longValue()==11),"other organization omitted");
        evidence.put(name,projects);return projects;
    }
    static void totals(String name,String due,String paid,String balance)throws Exception{
        List<Map<String,Object>> projects=read(name);eq(projects.size(),1,"same visible project");
        Map<String,Object> value=obj(projects.get(0).get("delivery_settlement"));
        eq(value.get("known_confirmed_amount"),due,"current confirmed amount");eq(value.get("known_paid_amount"),paid,"registered net paid");eq(value.get("known_balance"),balance,"current net balance");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Empty fixture directory and output required");
        Path dir=Path.of(args[0]);try(var files=Files.list(dir)){if(files.findAny().isPresent())throw new IllegalArgumentException("Empty directory required");}
        M06SettlementBridgeTest.fixture(dir);configure("DASH-1",true,true);
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.open(10,"001","DASH-A",M06SettlementBridgeTest.total("133.00","2026-09-01",false),"D1","CONFIRMED","SYNTHETIC-DASHBOARD"));
        totals("unpaid","133.00","0.00","133.00");
        String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.payment("DASH-A","D1",day,"133.00","SYNTHETIC-PAYMENT","0002",2,false));
        totals("paid","133.00","133.00","0.00");
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.replace("DASH-A","D1",M06SettlementBridgeTest.total("80.00","2026-10-01",false),"D2","SYNTHETIC-CORRECTION"));
        totals("correctedBeforeRefund","80.00","133.00","-53.00");
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.payment("DASH-A","D2",day,"-53.00","SYNTHETIC-REFUND","0002",2,false));
        totals("refunded","80.00","80.00","0.00");
        try{DeliverySettlementIntegration.projectSettlement(M06SettlementBridgeTest.worker,11);throw new AssertionError("Cross-organization amount accepted");}catch(Api.ApiException e){eq(e.code,403,"cross-organization denied");}
        Db.exec("INSERT INTO dispatches VALUES(101,10,20,'SYNTHETIC-MISSING','2026-10-01','待发送')");
        List<Map<String,Object>> partial=read("missingClaim");Map<String,Object> value=obj(partial.get(0).get("delivery_settlement"));eq(value.get("status"),"INCOMPLETE","missing claim stays unknown");eq(value.get("confirmed_amount"),null,"full amount is unknown");eq(value.get("known_confirmed_amount"),"80.00","known subtotal retained");
        Db.exec("DELETE FROM dispatches WHERE id=101");
        configure("DASH-2",true,false);List<Map<String,Object>> denied=read("deliveryDenied");eq(denied.size(),1,"demand-readable project still visible");eq(denied.get(0).get("delivery_settlement"),null,"delivery denial removes amounts");
        configure("DASH-3",false,true);List<Map<String,Object>> limited=read("limitedProject");eq(limited.size(),1,"module-only project visible");check(Boolean.TRUE.equals(obj(limited.get(0).get("read_access")).get("limited")),"limited projection");check(!limited.get(0).containsKey("delivery_settlement"),"limited project never exposes amounts");
        evidence.put("checks",checks);evidence.put("source_kind","SYNTHETIC_LEDGER_REAL_AUTH_API_HANDLE_PROJECTS");evidence.put("reports_read_granted",false);evidence.put("whole_database_read_only",true);
        Files.writeString(Path.of(args[1]),Json.write(evidence));System.out.println("Dashboard existing project API checks passed: "+checks);
    }
}
