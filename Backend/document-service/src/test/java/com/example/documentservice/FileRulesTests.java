package com.example.documentservice;

import com.example.documentservice.domain.FileRules;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class FileRulesTests {
    @Test void validatesDeclarationsAndMagicBytes(){
        assertThatThrownBy(()->FileRules.declared("../report.pdf","application/pdf",100,1000,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FileRules.declared("report.exe","application/pdf",100,1000,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FileRules.declared("report.pdf","application/pdf",1001,1000,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThat(FileRules.matches("%PDF-1.7".getBytes(),"application/pdf")).isTrue();
        assertThat(FileRules.matches("MZ executable".getBytes(),"application/pdf")).isFalse();
        assertThat(FileRules.matches("GIF89a".getBytes(),"image/gif")).isTrue();
        assertThat(FileRules.sha256(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
    byte[] zip(String... paths) throws IOException {var bytes=new ByteArrayOutputStream();try(var z=new ZipOutputStream(bytes)){for(String path:paths){z.putNextEntry(new ZipEntry(path));z.write("<xml/>".getBytes());z.closeEntry();}}return bytes.toByteArray();}
    @Test void officeContainersMustMatchTheirDeclaredFamilyAndExcludeMacros() throws Exception {
        String docx=FileRules.TYPES.get("docx");assertThat(FileRules.matches(zip("[Content_Types].xml","word/document.xml"),docx)).isTrue();
        assertThat(FileRules.matches(zip("[Content_Types].xml","xl/workbook.xml"),docx)).isFalse();
        assertThat(FileRules.matches(zip("[Content_Types].xml","word/document.xml","word/vbaProject.bin"),docx)).isFalse();
        assertThat(FileRules.matches(zip("[Content_Types].xml","word/document.xml","../escape"),docx)).isFalse();
    }
}
