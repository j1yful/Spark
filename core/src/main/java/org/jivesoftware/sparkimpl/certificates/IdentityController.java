package org.jivesoftware.sparkimpl.certificates;

import java.awt.HeadlessException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import javax.naming.InvalidNameException;
import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.jivesoftware.Spark;
import org.jivesoftware.resource.Res;
import org.jivesoftware.spark.ui.login.CertificateDialog;
import org.jivesoftware.spark.ui.login.CertificatesManagerSettingsPanel;
import org.jivesoftware.spark.ui.login.MutualAuthenticationSettingsPanel;
import org.jivesoftware.spark.util.log.Log;
import org.jivesoftware.sparkimpl.settings.local.LocalPreferences;

public class IdentityController extends CertManager {
    /**
     * This class is used for creating certificate signing request and self signed certificates.
     */

    private static String commonName, organizationUnit, organization,city, country;
    public final static File IDENTITY =           new File( Spark.getSparkUserHome() + File.separator + "security" + File.separator + "identitystore");
    public final static File SECURITY_DIRECTORY = new File( Spark.getSparkUserHome() + File.separator + "security"); 
    public static File CSR_FILE =                 new File( Spark.getSparkUserHome() + File.separator + "security" + File.separator + commonName + "_csr.pem");
    public static File KEY_FILE =                 new File( Spark.getSparkUserHome() + File.separator + "security" + File.separator + commonName + "_key.pem");
    public static File CERT_FILE =                new File (Spark.getSparkUserHome() + File.separator + "security" + File.separator + commonName + "_cert.pem");

    private KeyStore idStore;

    private final static String[] COLUMN_NAMES = { "Identity certificates" };
    private final static int NUMBER_OF_COLUMNS = COLUMN_NAMES.length;
    KeyPair keyPair;

    public IdentityController(LocalPreferences localPreferences) {
        Security.addProvider(new BouncyCastleProvider());
        loadKeyStores();
        if (localPreferences == null) {
            throw new IllegalArgumentException("localPreferences cannot be null");
        }
        this.localPreferences = localPreferences;


        createTableModel();

    }

    public void loadKeyStores() {

        idStore = openKeyStore(IDENTITY);
        fillTableListWithKeyStoreContent(idStore, null);
           
    }

    @Override
    public void overWriteKeyStores() {
        try (OutputStream outputStream = new FileOutputStream(IDENTITY)) {
            if (idStore != null) {
                idStore.store(outputStream, passwd);
            }
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            Log.error("Couldn't save TrustStore" , e);
        }

    }

    public KeyManagerFactory initKeyManagerFactory()
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, NoSuchProviderException {
        loadKeyStores();
        KeyManagerFactory keyManFact = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        keyManFact.init(idStore, IdentityController.passwd);

        return keyManFact;
    }

    public void setUpData(String commonName, String organizationUnit, String organization, String country,
            String city) {
        IdentityController.commonName = commonName;
        IdentityController.organizationUnit = organizationUnit;
        IdentityController.organization = organization;
        IdentityController.country = country;
        IdentityController.city = city;
        CSR_FILE =  new File( Spark.getSparkUserHome() + File.separator + "security" + File.separator + commonName + "_csr.pem");
        KEY_FILE =  new File( Spark.getSparkUserHome() + File.separator + "security" + File.separator + commonName + "_key.pem");
        CERT_FILE = new File (Spark.getSparkUserHome() + File.separator + "security" + File.separator + commonName + "_cert.pem");

    }

    public void createTableModel() {

        tableModel = new DefaultTableModel();
        tableModel.setColumnIdentifiers(COLUMN_NAMES);
        Object[] certEntry = new Object[NUMBER_OF_COLUMNS];
        for (CertificateModel cert : allCertificates) {
            if (cert.getSubjectCommonName() != null) {
                certEntry[0] = cert.getSubjectCommonName();
            } else {
                certEntry[0] = cert.getSubject();
            }
            tableModel.addRow(certEntry);
        }
    }

    @Override
    public void showCertificate() {
        CertificateDialog certDialog = new CertificateDialog(localPreferences,
                allCertificates.get(MutualAuthenticationSettingsPanel.getIdTable().getSelectedRow()), this,
                CertificateDialogReason.SHOW_ID_CERTIFICATE);
    }

    /**
     * Creates Certificate Signing Request.
     * 
     * @throws IOException
     * @throws OperatorCreationException
     */
    public PKCS10CertificationRequest createCSR(KeyPair keyPair) throws IOException, OperatorCreationException {

        X500Principal principal = new X500Principal(createX500NameString());
        PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(principal, keyPair.getPublic());
       
        JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
            ContentSigner signer = csBuilder.build(keyPair.getPrivate());
        PKCS10CertificationRequest csr = p10Builder.build(signer);
       
            return csr;
    }
    
    /**
     * Create string representation of the X500Principal name with fields as Common Name, Organization Unit, Organization, Location and Country.
     * @return String X500Name
     */
    private String createX500NameString() {
        StringBuilder sb = new StringBuilder();
        if (commonName == null || commonName.isEmpty()) {
            throw new IllegalArgumentException("Common Name cannot be empty");
        } else {
            sb.append("CN=" + commonName);
        }
        if (organizationUnit != null && !organizationUnit.isEmpty()) {
            sb.append(", OU=" + organizationUnit);
        }
        if (organization != null && !organization.isEmpty()) {
            sb.append(", O=" + organization);
        }
        if (city != null && !city.isEmpty()) {
            sb.append(", L=" + city);
        }
        if (country != null && !country.isEmpty()) {
            sb.append(", C=" + country);
        }
        return sb.toString();

    }

    public static DefaultTableModel getTableModel() {
        return tableModel;
    }
    /**
     * This method add certificate from file (*.pem) to Identity Store.
     * 
     * @throws KeyStoreException
     */
    @Override
    public void deleteEntry(String alias) throws KeyStoreException {
        int dialogButton = JOptionPane.YES_NO_OPTION;
        int dialogValue = JOptionPane.showConfirmDialog(null, Res.getString("dialog.certificate.sure.to.delete"), null, dialogButton);
        if (dialogValue == JOptionPane.YES_OPTION) {
            idStore.deleteEntry(alias);
            JOptionPane.showMessageDialog(null, Res.getString("dialog.certificate.has.been.deleted"));
            CertificateModel model = null;
            for (CertificateModel certModel : allCertificates) {
                if (certModel.getAlias().equals(alias)) {
                    model = certModel;
                }
            }
            allCertificates.remove(model);
        }
        refreshCertTable();
    }

    @Override
    public void addOrRemoveFromExceptionList(boolean checked) {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isOnExceptionList(CertificateModel cert) {
        // TODO Auto-generated method stub
        return false;
    }

    /**
     * Refresh certificate table to make visible changes in it's model
     */
    @Override
    public void refreshCertTable() {
        createTableModel();
        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                CertificatesManagerSettingsPanel.getCertTable().setModel(tableModel);
                tableModel.fireTableDataChanged();
            }
        });
    }

    /**
     * This method extract key and certificate from file (*.pem) and add it to the Identity Store.
     * 
     * @param file with certificate that is added and private key entry
     * @throws IOException
     * @throws KeyStoreException
     * @throws NoSuchAlgorithmException
     * @throws CertificateException
     * @throws UnrecoverableKeyException
     * @throws InvalidKeySpecException
     * @throws HeadlessException
     * @throws InvalidNameException
     */
    @Override
    public void addEntryToKeyStore(File file) throws IOException, CertificateException, InvalidKeySpecException,
            NoSuchAlgorithmException, KeyStoreException, InvalidNameException {

        byte[] certAndKey = Files.readAllBytes(file.toPath());
        byte[] certBytes = PemHelper.parseDERFromPEM(certAndKey,
                PemHelper.knowDelimeter(certAndKey, PemHelper.typeOfDelimeter.CERT_BEGIN),
                PemHelper.knowDelimeter(certAndKey, PemHelper.typeOfDelimeter.CERT_END));

        X509Certificate addedCert = PemHelper.generateCertificateFromDER(certBytes);

        byte[] keyBytes = PemHelper.parseDERFromPEM(certAndKey,
                PemHelper.knowDelimeter(certAndKey, PemHelper.typeOfDelimeter.KEY_BEGIN),
                PemHelper.knowDelimeter(certAndKey, PemHelper.typeOfDelimeter.KEY_END));

        PrivateKey key = PemHelper.generatePrivateKeyFromDER(keyBytes);

        CertificateModel certModel = new CertificateModel(addedCert);
        if (checkForSameCertificate(addedCert) == false) {
            showCertificate(certModel, CertificateDialogReason.ADD_ID_CERTIFICATE);
        }
        // value of addToKeyStore is changed by setter in CertificateDialog
        if (addToKeystore == true) {
            addToKeystore = false;

            String alias = useCommonNameAsAlias(addedCert);
            X509Certificate[] chain = {addedCert};
            
            idStore.setKeyEntry(alias, key, passwd, chain);
            allCertificates.add(new CertificateModel(addedCert));
            refreshCertTable();
            JOptionPane.showMessageDialog(null, Res.getString("dialog.certificate.has.been.added"));
        }

        
       
    }
    
    @Override
    protected boolean checkForSameAlias(String alias) throws HeadlessException, KeyStoreException {
        return idStore.containsAlias(alias);
    }

    public KeyPair createKeyPair() throws NoSuchAlgorithmException, NoSuchProviderException, FileNotFoundException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BC" );
        generator.initialize(2048, new SecureRandom());
        keyPair = generator.generateKeyPair();
        PrivateKey privKey = keyPair.getPrivate();
                    
        return keyPair;
    }

    public X509Certificate createSelfSignedCertificate(KeyPair keyPair) throws NoSuchAlgorithmException, NoSuchProviderException, CertIOException, OperatorCreationException, CertificateException {

        long serial = System.currentTimeMillis();
        SubjectPublicKeyInfo keyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
        X500Name name = new X500Name(createX500NameString());
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(name, 
                                                                            BigInteger.valueOf(serial), 
                                                                            new Date(System.currentTimeMillis() - 1000000000), 
                                                                            new Date(System.currentTimeMillis() + 1000000000),
                                                                            name, 
                                                                            keyInfo
                                                                            );
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true)); 
        certBuilder.addExtension(Extension.keyUsage,         true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        certBuilder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
    
        JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
        ContentSigner signer = csBuilder.build(keyPair.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);
        return cert;
    

        
}
}