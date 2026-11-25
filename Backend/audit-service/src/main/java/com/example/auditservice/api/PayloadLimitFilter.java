package com.example.auditservice.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Limits JSON bodies even when Content-Length is absent or requests are chunked. */
final class PayloadLimitFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    PayloadLimitFilter(ObjectMapper json){this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!request.getMethod().equals("POST")){chain.doFilter(request,response);return;}
        byte[] bytes=request.getInputStream().readNBytes(256*1024+1);
        if(bytes.length>256*1024){response.setStatus(413);response.setContentType("application/problem+json");response.getWriter().write(json.writeValueAsString(Problems.response(413,"PAYLOAD_TOO_LARGE","Audit JSON requests must fit in 256 KiB.",request).getBody()));return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
}
