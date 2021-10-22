package de.htwg.aume.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;

import de.htwg.aume.model.AvatarAnimal;
import de.htwg.aume.model.Member;
import de.htwg.aume.model.Session;
import lombok.val;

@ExtendWith(SpringExtension.class)
@DataMongoTest
public class SessionRepositoryTest {

	@Autowired
	private SessionRepository sessionRepo;

	@Test
	public void saveSession_returnsSession() {
		val sessionID = UUID.randomUUID();
		val adminID = UUID.randomUUID();
		val membersID = UUID.randomUUID();
		val session = new Session(sessionID, adminID, membersID, new ArrayList<Member>());

		assertEquals(session, sessionRepo.save(session));
	}

	@Test
	public void addMemberToSession_addsMember() {
		val sessionID = UUID.randomUUID();
		val adminID = UUID.randomUUID();
		val membersID = UUID.randomUUID();
		val member = new Member(UUID.randomUUID(), "John", "0x0a0a0a", AvatarAnimal.CAMEL, Optional.empty());
		val members = new ArrayList<Member>();
		members.add(member);
		val session = new Session(sessionID, adminID, membersID, members);

		assertEquals(session, sessionRepo.save(session));
	}

}