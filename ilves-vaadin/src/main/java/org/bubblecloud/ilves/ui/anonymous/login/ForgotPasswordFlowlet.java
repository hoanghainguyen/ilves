/**
 * Copyright 2013 Tommi S.E. Laukkanen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bubblecloud.ilves.ui.anonymous.login;

import com.vaadin.data.Property;
import com.vaadin.data.util.ObjectProperty;
import com.vaadin.data.util.PropertysetItem;
import com.vaadin.data.validator.EmailValidator;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServletRequest;
import com.vaadin.ui.*;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import org.apache.log4j.Logger;
import org.bubblecloud.ilves.component.flow.AbstractFlowlet;
import org.bubblecloud.ilves.component.grid.FieldDescriptor;
import org.bubblecloud.ilves.component.grid.ValidatingEditor;
import org.bubblecloud.ilves.component.grid.ValidatingEditorStateListener;
import org.bubblecloud.ilves.exception.SiteException;
import org.bubblecloud.ilves.model.Company;
import org.bubblecloud.ilves.model.EmailPasswordReset;
import org.bubblecloud.ilves.model.User;
import org.bubblecloud.ilves.security.UserDao;
import org.bubblecloud.ilves.util.EmailUtil;
import org.bubblecloud.ilves.util.PropertiesUtil;
import org.bubblecloud.ilves.util.StringUtil;

import javax.persistence.EntityManager;
import javax.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Register Flowlet.
 *
 * @author Tommi S.E. Laukkanen
 */
public final class ForgotPasswordFlowlet extends AbstractFlowlet {

    /** The logger. */
    private static final Logger LOGGER = Logger.getLogger(ForgotPasswordFlowlet.class);

    /** Default serial version UID. */
    private static final long serialVersionUID = 1L;
    /** Password reset PIN property. */
    private Property pinProperty;
    /** Original password property. */
    private Property emailAddressProperty;
    /** Validating editor. */
    private ValidatingEditor editor;

    @Override
    public String getFlowletKey() {
        return "forgot-password";
    }

    /**
     * Reset data.
     */
    public void reset() {
        final PropertysetItem item = new PropertysetItem();
        pinProperty.setValue(Integer.toString((int) ((Math.random() + 1) / 2 * 9999)));
        item.addItemProperty("pin", pinProperty);
        emailAddressProperty.setValue("");
        item.addItemProperty("emailAddress", emailAddressProperty);
        editor.setItem(item, true);
    }

    @Override
    public void initialize() {
        pinProperty = new ObjectProperty<String>(null, String.class);
        emailAddressProperty = new ObjectProperty<String>(null, String.class);

        final List<FieldDescriptor> fieldDescriptors = new ArrayList<FieldDescriptor>();

        fieldDescriptors.add(new FieldDescriptor("pin", getSite().localize("input-password-reset-pin"),
                TextField.class, null, 150, null, String.class, null,
                true, true, true
        ));
        fieldDescriptors.add(new FieldDescriptor("emailAddress", getSite().localize("input-email-address"),
                TextField.class, null, 150, null, String.class, null,
                false, true, true
                ).addValidator(new EmailValidator("Email address is not valid.")));

        editor = new ValidatingEditor(fieldDescriptors);

        final Button resetPasswordButton = new Button(getSite().localize("button-reset-password"));
        resetPasswordButton.addClickListener(new ClickListener() {
            /** The default serial version ID. */
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(final ClickEvent event) {
                editor.commit();
                final EntityManager entityManager = getSite().getSiteContext().getObject(EntityManager.class);
                final Company company = getSite().getSiteContext().getObject(Company.class);

                final User user = UserDao.getUser(entityManager, company, (String) emailAddressProperty.getValue());
                if (user == null) {
                    Notification.show(getSite().localize("message-user-email-address-not-registered"),
                            Notification.Type.WARNING_MESSAGE);
                    return;
                }

                final List<EmailPasswordReset> emailPasswordResets = UserDao.getEmailPasswordResetByEmailAddress(
                        entityManager, user);
                final Date now = new Date();

                for (final EmailPasswordReset emailPasswordReset : emailPasswordResets) {
                    if (now.getTime() - emailPasswordReset.getCreated().getTime() < 24 * 60 * 60 * 1000) {
                        Notification.show(getSite().localize("message-password-reset-email-already-sent"),
                                Notification.Type.ERROR_MESSAGE);
                        return;
                    } else {
                        entityManager.getTransaction().begin();
                        try {
                            entityManager.remove(emailPasswordReset);
                            entityManager.getTransaction().commit();
                        } catch (final Exception e) {
                            if (entityManager.getTransaction().isActive()) {
                                entityManager.getTransaction().rollback();
                            }
                            throw new SiteException("Error removing old email password reset.", e);
                        }
                    }
                }

                try {
                    final String pin = (String) pinProperty.getValue();
                    final byte[] pinAndSaltBytes = (user.getEmailAddress() + ":" + pin).getBytes("UTF-8");
                    final MessageDigest md = MessageDigest.getInstance("SHA-256");
                    final byte[] pinAndSaltDigest = md.digest(pinAndSaltBytes);

                    final EmailPasswordReset emailPasswordReset = new EmailPasswordReset();
                    emailPasswordReset.setUser(user);
                    emailPasswordReset.setPinHash(StringUtil.toHexString(pinAndSaltDigest));
                    emailPasswordReset.setCreated(now);

                    entityManager.getTransaction().begin();
                    try {
                        entityManager.persist(emailPasswordReset);
                        entityManager.getTransaction().commit();
                    } catch (final Exception e) {
                        if (entityManager.getTransaction().isActive()) {
                            entityManager.getTransaction().rollback();
                        }
                        throw new SiteException("Error saving email password reset", e);
                    }

                    final String url = company.getUrl() +
                            "#!reset/" + emailPasswordReset.getEmailPasswordResetId();

                    final Thread emailThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            EmailUtil.send(
                                    user.getEmailAddress(), company.getSupportEmailAddress(), "Password Reset Link",
                                    "Password reset has been requested for your user account." +
                                            "You can perform the reset using the following link: " + url);
                        }
                    });
                    emailThread.start();

                    Notification.show(getSite().localize("message-password-reset-email-sent")
                                    + getSite().localize("message-your-password-reset-pin-is") + pin,
                            Notification.Type.WARNING_MESSAGE);

                    final HttpServletRequest request = ((VaadinServletRequest) VaadinService.getCurrentRequest())
                            .getHttpServletRequest();
                    LOGGER.info("Password reset email sent to " + user.getEmailAddress()
                            + " (IP: " + request.getRemoteHost() + ":" + request.getRemotePort() + ")");

                    getFlow().back();
                } catch (final Exception e) {
                    LOGGER.error("Error preparing password reset.", e);
                    Notification.show(getSite().localize("message-password-reset-prepare-error"),
                            Notification.Type.WARNING_MESSAGE);
                }
                reset();
            }
        });

        editor.addListener(new ValidatingEditorStateListener() {
            @Override
            public void editorStateChanged(final ValidatingEditor source) {
                if (source.isValid()) {
                    resetPasswordButton.setEnabled(true);
                } else {
                    resetPasswordButton.setEnabled(false);
                }
            }
        });

        reset();

        final VerticalLayout panel = new VerticalLayout();
        panel.addComponent(editor);
        panel.addComponent(resetPasswordButton);
        panel.setSpacing(true);

        final HorizontalLayout mainLayout = new HorizontalLayout();
        mainLayout.addComponent(panel);

        setViewContent(mainLayout);
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public void enter() {
    }

}
