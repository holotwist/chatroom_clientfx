module com.unilabs.chatroom_clientfx {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;
    requires org.json;
    requires java.net.http;

    opens com.unilabs.chatroom_clientfx to javafx.graphics;
    opens com.unilabs.chatroom_clientfx.controller to javafx.fxml;

}