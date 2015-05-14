package org.bubblecloud.ilves.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bubblecloud.ilves.security.OpenAuthService;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Map;

/**
 * Created by tlaukkan on 5/14/2015.
 */
public class OAuthServiceTest {

    @Test
    public void testGet() throws Exception {
        final String response = OpenAuthService.get("https://api.github.com/user/emails", "e902307ab2dec9d653dd6ee85bd9a78fdafd52de");
        ObjectMapper objectMapper = new ObjectMapper();
        final ArrayList<Map<String, Object>> emailList = objectMapper.readValue(response, ArrayList.class);
        System.out.println(emailList);
        Assert.assertEquals(1, emailList.size());
        Assert.assertEquals("tommi.s.e.laukkanen@gmail.com", emailList.get(0).get("email"));
        Assert.assertTrue((Boolean) emailList.get(0).get("primary"));
        Assert.assertTrue((Boolean) emailList.get(0).get("verified"));
    }

    @Test
    public void getGetEmail() throws Exception {
        final String response = OpenAuthService.getEmail("e902307ab2dec9d653dd6ee85bd9a78fdafd52de");
        Assert.assertEquals("tommi.s.e.laukkanen@gmail.com", response);
    }
}
