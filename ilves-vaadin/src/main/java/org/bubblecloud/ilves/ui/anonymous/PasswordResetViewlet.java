package org.bubblecloud.ilves.ui.anonymous;

import com.vaadin.data.Property;
import com.vaadin.data.util.ObjectProperty;
import com.vaadin.data.util.PropertysetItem;
import com.vaadin.data.validator.StringLengthValidator;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServletRequest;
import com.vaadin.shared.ui.MarginInfo;
import com.vaadin.shared.ui.label.ContentMode;
import com.vaadin.ui.*;
import org.apache.log4j.Logger;
import org.bubblecloud.ilves.component.grid.FieldDescriptor;
import org.bubblecloud.ilves.component.grid.ValidatingEditor;
import org.bubblecloud.ilves.component.grid.ValidatingEditorStateListener;
import org.bubblecloud.ilves.exception.SiteException;
import org.bubblecloud.ilves.model.Company;
import org.bubblecloud.ilves.model.EmailPasswordReset;
import org.bubblecloud.ilves.model.User;
import org.bubblecloud.ilves.security.PasswordLoginUtil;
import org.bubblecloud.ilves.security.UserDao;
import org.bubblecloud.ilves.site.AbstractViewlet;
import org.bubblecloud.ilves.util.StringUtil;

import javax.persistence.EntityManager;
import javax.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Viewlet for email validation.
 */
public class PasswordResetViewlet extends AbstractViewlet {
    /** The logger. */
    private static final Logger LOGGER = Logger.getLogger(PasswordResetViewlet.class);
    /** Validating editor. */
    private ValidatingEditor editor;
    /** Password reset PIN property. */
    private Property pinProperty;
    /** Password property. */
    private Property passwordProperty;
    /** Password characters. */
    private static final String PASSWORD_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!#%&,.-+*";
    /** Random for password generation. */
    private static SecureRandom random = new SecureRandom();

    @Override
    public final void enter(final String parameters) {
        final HttpServletRequest request = ((VaadinServletRequest) VaadinService.getCurrentRequest())
                .getHttpServletRequest();

        final Company company = getSite().getSiteContext().getObject(Company.class);
        if (!company.isEmailPasswordReset()) {
            LOGGER.error("Password reset attempted but email password reset is disabled in company. "
                    + "Email password reset ID: " + parameters
                    + " (IP: " + request.getRemoteHost() + ":" + request.getRemotePort() + ")");
            return;
        }

        final String emailPasswordResetId = parameters;
        final EntityManager entityManager = getSite().getSiteContext().getObject(EntityManager.class);

        final EmailPasswordReset emailPasswordReset
                = UserDao.getEmailPasswordReset(entityManager, emailPasswordResetId);

        if (emailPasswordReset != null) {
            final User user = emailPasswordReset.getUser();
            if (!user.getOwner().getCompanyId().equals(company.getCompanyId())) {
                LOGGER.error("Password reset attempted through wrong company: " + user.getEmailAddress()
                        + " (IP: " + request.getRemoteHost() + ":" + request.getRemotePort() + ")");
                return;
            }
            final List<FieldDescriptor> fieldDescriptors = new ArrayList<FieldDescriptor>();
            fieldDescriptors.add(new FieldDescriptor("pin", getSite().localize("input-password-reset-pin"),
                    TextField.class, null, 150, null, String.class, "",
                    false, true, true
            ).addValidator(new StringLengthValidator("Invalid PIN length.", 4, 4, false)));
            fieldDescriptors.add(new FieldDescriptor("password", getSite().localize("input-password"),
                    TextField.class, null, 150, null, String.class, "",
                    true, true, false
            ));
            editor = new ValidatingEditor(fieldDescriptors);

            pinProperty = new ObjectProperty<String>(null, String.class);
            passwordProperty = new ObjectProperty<String>(null, String.class);
            reset();

            final Button submitButton = new Button(getSite().localize("button-submit"));
            submitButton.setEnabled(false);
            submitButton.addClickListener(new Button.ClickListener() {
                /** The default serial version ID. */
                private static final long serialVersionUID = 1L;

                @Override
                public void buttonClick(final Button.ClickEvent event) {
                    editor.commit();
                    try {
                        final String pin = (String) pinProperty.getValue();
                        final byte[] pinAndSaltBytes = (user.getEmailAddress() + ":" + pin).getBytes("UTF-8");
                        final MessageDigest pinMd = MessageDigest.getInstance("SHA-256");
                        final byte[] pinAndSaltDigest = pinMd.digest(pinAndSaltBytes);
                        final String pinAndSaltHash = StringUtil.toHexString(pinAndSaltDigest);

                        if (emailPasswordReset.getPinHash().equals(pinAndSaltHash)) {
                            final String password = generatePassword();
                            PasswordLoginUtil.setUserPasswordHash(user.getOwner(), user, password.toCharArray());
                            passwordProperty.setValue(password);
                            submitButton.setEnabled(false);
                            fieldDescriptors.get(0).setReadOnly(true);
                            reset();
                            entityManager.getTransaction().begin();
                            try {
                                entityManager.remove(emailPasswordReset);
                                entityManager.persist(user);
                                entityManager.getTransaction().commit();
                            } catch (final Exception e) {
                                if (entityManager.getTransaction().isActive()) {
                                    entityManager.getTransaction().rollback();
                                }
                                throw new SiteException("Error reseting password", e);
                            }
                            LOGGER.info("Password reset: " + user.getEmailAddress()
                                    + " (IP: " + request.getRemoteHost() + ":" + request.getRemotePort() + ")");
                            Notification.show(getSite().localize("message-password-reset-success"),
                                    Notification.Type.HUMANIZED_MESSAGE);
                        } else {
                            entityManager.getTransaction().begin();
                            try {
                                entityManager.remove(emailPasswordReset);
                                entityManager.getTransaction().commit();
                            } catch (final Exception e) {
                                if (entityManager.getTransaction().isActive()) {
                                    entityManager.getTransaction().rollback();
                                }
                                throw new SiteException("Error removing email reset password row.", e);
                            }
                            LOGGER.info("Password reset, invalid pin: " + user.getEmailAddress()
                                    + " (IP: " + request.getRemoteHost() + ":" + request.getRemotePort() + ")");
                            Notification.show(getSite().localize("message-invalid-password-reset-pin"),
                                    Notification.Type.WARNING_MESSAGE);
                            final Company company = getSite().getSiteContext().getObject(Company.class);
                            //getUI().getPage().setLocation(company.getUrl() + "#!reset");
                            UI.getCurrent().getNavigator().navigateTo("reset");

                            getSession().close();
                        }

                    } catch (final Exception e) {
                        LOGGER.error("Error adding user: " + user.getEmailAddress()
                                + " (IP: " + request.getRemoteHost() + ":" + request.getRemotePort() + ")", e);
                        Notification.show(getSite().localize("message-password-reset-error"),
                                Notification.Type.WARNING_MESSAGE);
                    }
                }
            });

            editor.addListener(new ValidatingEditorStateListener() {
                @Override
                public void editorStateChanged(final ValidatingEditor source) {
                    if (source.isValid()) {
                        submitButton.setEnabled(true);
                    } else {
                        submitButton.setEnabled(false);
                    }
                }
            });

            final HorizontalLayout titleLayout = new HorizontalLayout();
            titleLayout.setMargin(new MarginInfo(true, false, true, false));
            titleLayout.setSpacing(true);
            final Embedded titleIcon = new Embedded(null, getSite().getIcon("view-icon-password-reset"));
            titleIcon.setWidth(32, Unit.PIXELS);
            titleIcon.setHeight(32, Unit.PIXELS);
            titleLayout.addComponent(titleIcon);
            final Label titleLabel = new Label(
                    "<h1>" + getSite().localize("view-password-reset") + "</h1>", ContentMode.HTML);
            titleLayout.addComponent(titleLabel);

            final VerticalLayout panel = new VerticalLayout();
            panel.addComponent(titleLayout);
            panel.addComponent(editor);
            panel.addComponent(submitButton);
            panel.setSpacing(true);
            panel.setMargin(true);
            setCompositionRoot(panel);
        } else {
            Notification.show(getSite().localize("message-password-reset-consumed"),
                    Notification.Type.WARNING_MESSAGE);
        }

    }

    private PropertysetItem reset() {
        final PropertysetItem item = new PropertysetItem();
        item.addItemProperty("pin", pinProperty);
        item.addItemProperty("password", passwordProperty);
        editor.setItem(item, true);
        return item;
    }

    /**
     * Generates random password.
     *
     * @return the random password.
     */
    private String generatePassword() {
        StringBuilder sb = new StringBuilder();
        synchronized (random) {
            for( int i = 0; i < 10; i++ ) {
                sb.append(PASSWORD_CHARACTERS.charAt(random.nextInt(PASSWORD_CHARACTERS.length())));
            }
        }
        return sb.toString();
    }
}
